package dev.jellyfinnative.data.downloads

import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadProgress
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.download
import dev.jellyfinnative.data.downloads.DownloadFixtures.file
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.model.SizeCertainty
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import dev.jellyfinnative.data.downloads.storage.DownloadVolume
import dev.jellyfinnative.data.downloads.storage.StorageLocationManager
import dev.jellyfinnative.data.downloads.storage.StorageSelection
import dev.jellyfinnative.data.downloads.work.DownloadScheduler
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [DownloadRepositoryImpl] — the surface every feature module sees.
 *
 * Two things are pinned here that nothing else covers: the `DownloadStatus` → `DownloadState`
 * mapping every badge in the app renders from, and the ordering rules of the mutations (stop the
 * queue *before* unlinking files, restart it after).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryImplTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()
    private val enqueuer = mockk<DownloadEnqueuer>()
    private val deleter = mockk<DownloadDeleter>()
    private val scheduler = mockk<DownloadScheduler>(relaxUnitFun = true)
    private val storage = mockk<DownloadStorage>(relaxed = true)
    private val locations = mockk<StorageLocationManager>(relaxUnitFun = true)
    private val preferences = mockk<AppPreferences>(relaxUnitFun = true)
    private val sessionRepository = mockk<SessionRepository>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val selectedVolumeId = MutableStateFlow<String?>(null)

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns MutableStateFlow(LOGGED_IN)
        every { preferences.downloadOverWifiOnly } returns flowOf(true)
        every { locations.selectedVolumeId } returns selectedVolumeId
        every { locations.resolve(any()) } answers { selectionFor(firstArg()) }
        // The tests that switch volume start out writing to the primary one.
        every { locations.activeVolume() } returns PRIMARY_VOLUME
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { scheduler.ensureRunning() } returns Unit
        coEvery { scheduler.restart() } returns Unit
        coEvery { deleter.delete(any()) } returns 0L
        coEvery { downloadDao.pending() } returns emptyList()
        coEvery { downloadDao.get(any()) } returns null
        coEvery { itemDao.getItems(any()) } returns emptyList()
    }

    // ---- badge states ---------------------------------------------------------------------------

    @Test
    fun `each status maps onto the badge state the UI draws`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                flowOf(
                    listOf(
                        progress(uuid(1), DownloadStatus.QUEUED),
                        progress(uuid(2), DownloadStatus.DOWNLOADING, downloaded = 25L, total = 100L),
                        progress(uuid(3), DownloadStatus.PAUSED),
                        progress(uuid(4), DownloadStatus.DOWNLOADED),
                        progress(uuid(5), DownloadStatus.ERROR),
                    ),
                )

            repository().observeStates().test {
                awaitItem() shouldContainExactly
                    mapOf(
                        uuid(1).toString() to DownloadState.Queued,
                        uuid(2).toString() to DownloadState.Downloading(progress = 0.25f),
                        uuid(3).toString() to DownloadState.Paused,
                        uuid(4).toString() to DownloadState.Downloaded,
                        uuid(5).toString() to DownloadState.Failed,
                    )
                awaitComplete()
            }
        }

    @Test
    fun `a cancelled row reads as not downloaded`() =
        runTest {
            // CANCELLED only exists between a cancel and the row's deletion; a badge for it would
            // be a badge for a state the user already asked to be rid of.
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.CANCELLED)))

            repository().observeStates().test {
                awaitItem()[uuid(1).toString()] shouldBe DownloadState.NotDownloaded
                awaitComplete()
            }
        }

    @Test
    fun `a download with no known size reports zero progress rather than complete`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING, downloaded = 500L, total = 0L)))

            repository().observeStates().test {
                awaitItem()[uuid(1).toString()] shouldBe DownloadState.Downloading(progress = 0f)
                awaitComplete()
            }
        }

    // ---- the download list ----------------------------------------------------------------------

    @Test
    fun `a download row is joined to its cached item`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(listOf(DownloadWithFiles(download(), listOf(file(id = 1, bytesDownloaded = 900L)))))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(ITEM_ROW)
            every { itemMapper.toDomainOrNull(ITEM_ROW, null) } returns MOVIE

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.itemId shouldBe uuid(1).toString()
                row.item shouldBe MOVIE
                row.bytesOnDisk shouldBe 900L
                awaitComplete()
            }
        }

    @Test
    fun `an Original download's row carries the exact quality the server reported`() =
        runTest {
            // The UI's wording is decided from `quality`, `sizeIsExact` and `projectedBytes`
            // together (DECISIONS.md, 2026-07-29 and the v6 entry) — each must survive the Room
            // round trip untouched.
            every { downloadDao.observeAll() } returns
                flowOf(listOf(DownloadWithFiles(download(quality = DownloadQuality.ORIGINAL), emptyList())))

            repository().observeDownloads().test {
                awaitItem().single().quality shouldBe DownloadQuality.ORIGINAL
                awaitComplete()
            }
        }

    @Test
    fun `a capped download's row carries the quality that stamped the ceiling`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(listOf(DownloadWithFiles(download(quality = DownloadQuality.LOW), emptyList())))

            repository().observeDownloads().test {
                awaitItem().single().quality shouldBe DownloadQuality.LOW
                awaitComplete()
            }
        }

    @Test
    fun `a projected size reaches the row that has to divide by it`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(
                    listOf(
                        DownloadWithFiles(
                            download(quality = DownloadQuality.LOW, bytesTotal = 552L, projectedBytes = 301L),
                            emptyList(),
                        ),
                    ),
                )

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.projectedBytes shouldBe 301L
                row.displayTotalBytes shouldBe 301L
                row.sizeCertainty shouldBe SizeCertainty.APPROXIMATE
                awaitComplete()
            }
        }

    @Test
    fun `a row with no projection still reports its ceiling and says so`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(
                    listOf(
                        DownloadWithFiles(download(quality = DownloadQuality.LOW, bytesTotal = 552L), emptyList()),
                    ),
                )

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.projectedBytes.shouldBeNull()
                row.displayTotalBytes shouldBe 552L
                row.sizeCertainty shouldBe SizeCertainty.CEILING
                awaitComplete()
            }
        }

    @Test
    fun `a stream-copy row is carried through as exact, not as a ceiling`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(
                    listOf(
                        DownloadWithFiles(
                            download(quality = DownloadQuality.HIGH, bytesTotal = 2_800L, sizeIsExact = true),
                            emptyList(),
                        ),
                    ),
                )

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.sizeIsExact shouldBe true
                row.sizeCertainty shouldBe SizeCertainty.EXACT
                awaitComplete()
            }
        }

    @Test
    fun `a download whose item row is gone still lists, so its files can be deleted`() =
        runTest {
            every { downloadDao.observeAll() } returns flowOf(listOf(DownloadWithFiles(download(), emptyList())))
            coEvery { itemDao.getItems(any()) } returns emptyList()

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.item shouldBe null
                // The denormalised title is why the row is still usable.
                row.title shouldBe "Arrival"
                awaitComplete()
            }
        }

    // ---- mutations ------------------------------------------------------------------------------

    @Test
    fun `enqueue caches the item and then starts the queue`() =
        runTest {
            coEvery { enqueuer.enqueue(uuid(1), uuid(99)) } returns AppResult.Success(listOf(download()))

            repository().enqueue(uuid(1).toString()).shouldBeInstanceOf<AppResult.Success<Unit>>()

            coVerifyOrder {
                enqueuer.enqueue(uuid(1), uuid(99))
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `a failed enqueue does not start the queue`() =
        runTest {
            coEvery { enqueuer.enqueue(any(), any()) } returns AppResult.Failure(AppError.Network())

            repository().enqueue(uuid(1).toString()).shouldBeInstanceOf<AppResult.Failure>()

            coVerify(exactly = 0) { scheduler.ensureRunning() }
        }

    @Test
    fun `enqueueing a season starts the queue once, however many episodes it expanded to`() =
        runTest {
            // The repository does not know or care that one tap became ten downloads: the queue is
            // idempotent and drains whatever Room holds (DECISIONS.md, 2026-07-29).
            coEvery { enqueuer.enqueue(uuid(11), uuid(99)) } returns
                AppResult.Success(listOf(download(itemId = uuid(2)), download(itemId = uuid(3))))

            repository().enqueue(uuid(11).toString()).shouldBeInstanceOf<AppResult.Success<Unit>>()

            coVerify(exactly = 1) { scheduler.ensureRunning() }
        }

    @Test
    fun `enqueueing while signed out is unauthorized, not a crash`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            val result = repository().enqueue(uuid(1).toString())

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Unauthorized>()
        }

    @Test
    fun `an unparseable item id is a NotFound`() =
        runTest {
            val result = repository().enqueue("not-a-uuid")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
        }

    @Test
    fun `pausing marks the row and interrupts the running job`() =
        runTest {
            repository().pause(uuid(1).toString())

            coVerifyOrder {
                downloadDao.setStatus(uuid(1), DownloadStatus.PAUSED, NOW, null)
                // Cancelling the work is the only way to interrupt a transfer that is already in
                // flight; the restart then picks up whatever is left.
                scheduler.stop()
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `pausing writes PAUSED and leaves the rest of the queue running`() =
        runTest {
            // The other half of the M9 pause bug (docs/POLISH.md): the repository's own writes must
            // leave exactly one status behind — `DownloadQueue` is what used to add a second one
            // when it saw the cancellation — and the queue must be brought back up with
            // `ensureRunning`, so items behind the paused one keep draining.
            repository().pause(uuid(1).toString())

            coVerify(exactly = 1) { downloadDao.setStatus(uuid(1), DownloadStatus.PAUSED, NOW, null) }
            coVerify(exactly = 0) { downloadDao.setStatus(any(), DownloadStatus.QUEUED, any(), any()) }
            coVerify(exactly = 1) { scheduler.ensureRunning() }
        }

    @Test
    fun `resuming re-queues the row and restarts with fresh constraints`() =
        runTest {
            repository().resume(uuid(1).toString())

            coVerifyOrder {
                downloadDao.setStatus(uuid(1), DownloadStatus.QUEUED, NOW, null)
                scheduler.restart()
            }
        }

    @Test
    fun `resuming gives the row its full retry budget back`() =
        runTest {
            // Otherwise *Retry* on a row that exhausted its attempts against a server that was down
            // would be worth exactly one more, and fail again on the first blip.
            repository().resume(uuid(1).toString())

            coVerify(exactly = 1) { downloadDao.clearAttempts(uuid(1)) }
        }

    @Test
    fun `deleting stops the queue before unlinking files`() =
        runTest {
            coEvery { deleter.delete(uuid(1)) } returns 2_100_000_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(2_100_000_000L)

            // The downloader must not be holding a handle to a file we are about to remove.
            coVerifyOrder {
                scheduler.stop()
                deleter.delete(uuid(1))
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `cancelling the item that is downloading runs the same cascade as a delete`() =
        runTest {
            // *Cancel* in the Queue tab, *Delete* in the Downloaded list and *Cancel* on the
            // notification are one operation; this pins that an in-flight item is no exception —
            // its files must not survive the row.
            coEvery { downloadDao.get(uuid(1)) } returns download(status = DownloadStatus.DOWNLOADING)
            coEvery { deleter.delete(uuid(1)) } returns 1_400_000_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(1_400_000_000L)

            coVerifyOrder {
                // Stop first: the transfer must not be holding a handle to a file we unlink.
                scheduler.stop()
                deleter.delete(uuid(1))
                // Something else may still be queued behind the cancelled item.
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `cancelling a merely queued item runs the cascade too`() =
        runTest {
            // A queued item can still have bytes on disk: it was interrupted mid-transfer and put
            // back in the queue, which is precisely the resume case.
            coEvery { downloadDao.get(uuid(1)) } returns download(status = DownloadStatus.QUEUED)
            coEvery { deleter.delete(uuid(1)) } returns 900_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(900_000L)

            coVerify(exactly = 1) { deleter.delete(uuid(1)) }
        }

    @Test
    fun `a failing delete is a Storage failure, not an exception`() =
        runTest {
            coEvery { deleter.delete(any()) } throws IllegalStateException("volume ejected")

            val result = repository().delete(uuid(1).toString())

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- the awaited stop (STAB-04) --------------------------------------------------------------

    @Test
    fun `nothing is unlinked until the queue has actually stopped`() =
        runTest {
            // WorkManager's cancellation is asynchronous, and `FileDownloader` re-creates the item
            // directory for every file it opens: a delete that runs while the transfer is still
            // alive gets its directory put straight back by a `mkdirs()` and written into until the
            // next `ensureActive()`. Those bytes are then invisible to the UI forever.
            val stopped = CompletableDeferred<Unit>()
            coEvery { scheduler.stop() } coAnswers { stopped.await() }

            val repository = repository(ioDispatcher = StandardTestDispatcher(testScheduler))
            val delete = async { repository.delete(uuid(1).toString()) }
            runCurrent()
            coVerify(exactly = 0) { deleter.delete(any()) }

            stopped.complete(Unit)
            delete.await()

            coVerify(exactly = 1) { deleter.delete(uuid(1)) }
        }

    // ---- bulk actions (STAB-09) ------------------------------------------------------------------

    @Test
    fun `pausing everything is one status write and one restart, not one per row`() =
        runTest {
            // The finding: a bulk action built out of single-item mutations issued a stop/start
            // cycle per row, so a forty-episode queue produced forty overlapping drains — each of
            // them running `requeueInterrupted` over rows another drain was still writing.
            val ids = listOf(uuid(1), uuid(2), uuid(3))

            repository().pauseAll(ids.map(java.util.UUID::toString)) shouldBe AppResult.Success(Unit)

            coVerifyOrder {
                downloadDao.setStatusIn(ids, DownloadStatus.PAUSED, NOW)
                scheduler.stop()
                scheduler.ensureRunning()
            }
            coVerify(exactly = 1) { scheduler.stop() }
            coVerify(exactly = 1) { scheduler.ensureRunning() }
            coVerify(exactly = 0) { downloadDao.setStatus(any(), any(), any(), any()) }
        }

    @Test
    fun `resuming everything is one transition and one restart`() =
        runTest {
            val ids = listOf(uuid(1), uuid(2))

            repository().resumeAll(ids.map(java.util.UUID::toString)) shouldBe AppResult.Success(Unit)

            coVerifyOrder {
                // One statement, and it clears the retry budget: a row that spent its attempts on a
                // server that was down must be worth a full set again now the user has asked.
                downloadDao.requeueForUser(ids, NOW)
                scheduler.restart()
            }
            coVerify(exactly = 1) { scheduler.restart() }
        }

    @Test
    fun `cancelling everything stops the queue once and starts it once`() =
        runTest {
            coEvery { deleter.delete(uuid(1)) } returns 100L
            coEvery { deleter.delete(uuid(2)) } returns 200L

            repository().deleteAll(listOf(uuid(1), uuid(2)).map(java.util.UUID::toString)) shouldBe
                AppResult.Success(300L)

            coVerifyOrder {
                scheduler.stop()
                deleter.delete(uuid(1))
                deleter.delete(uuid(2))
                scheduler.ensureRunning()
            }
            coVerify(exactly = 1) { scheduler.stop() }
        }

    @Test
    fun `a bulk action with no targets left touches neither Room nor the scheduler`() =
        runTest {
            // The queue can drain while the user is reaching for the button; stopping and
            // restarting the worker for an empty list would interrupt whatever took its place.
            repository().pauseAll(emptyList()) shouldBe AppResult.Success(Unit)
            repository().resumeAll(emptyList()) shouldBe AppResult.Success(Unit)
            repository().deleteAll(emptyList()) shouldBe AppResult.Success(0L)

            coVerify(exactly = 0) { scheduler.stop() }
            coVerify(exactly = 0) { scheduler.restart() }
            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `an unparseable id in a bulk action is a NotFound, and nothing is changed`() =
        runTest {
            val result = repository().pauseAll(listOf(uuid(1).toString(), "not-a-uuid"))

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { downloadDao.setStatusIn(any(), any(), any()) }
            coVerify(exactly = 0) { scheduler.stop() }
        }

    @Test
    fun `a failing bulk delete is a Storage failure, not an exception`() =
        runTest {
            coEvery { deleter.delete(any()) } throws IllegalStateException("volume ejected")

            val result = repository().deleteAll(listOf(uuid(1).toString()))

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- reordering -----------------------------------------------------------------------------

    @Test
    fun `moving an item renumbers the queue from zero`() =
        runTest {
            val moved = download(itemId = uuid(3), queuePosition = 7)
            coEvery { downloadDao.pending() } returns
                listOf(
                    download(itemId = uuid(1), queuePosition = 2),
                    download(itemId = uuid(2), queuePosition = 5),
                    moved,
                )
            coEvery { downloadDao.get(uuid(3)) } returns moved

            repository().move(uuid(3).toString(), position = 0)

            // Gaps left by completed items would otherwise make "position" mean something other
            // than "place in the list".
            coVerify { downloadDao.setQueuePosition(uuid(3), 0, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(1), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(2), 2, NOW) }
        }

    @Test
    fun `a position past the end of the queue clamps to the end`() =
        runTest {
            val moved = download(itemId = uuid(1), queuePosition = 0)
            coEvery { downloadDao.pending() } returns listOf(moved, download(itemId = uuid(2), queuePosition = 1))
            coEvery { downloadDao.get(uuid(1)) } returns moved

            repository().move(uuid(1).toString(), position = 99)

            coVerify { downloadDao.setQueuePosition(uuid(1), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(2), 0, NOW) }
        }

    // ---- preferences ----------------------------------------------------------------------------

    @Test
    fun `flipping Wi-Fi only re-applies the constraint to the running queue`() =
        runTest {
            repository().setWifiOnly(false)

            coVerifyOrder {
                preferences.setDownloadOverWifiOnly(false)
                // A running job keeps the constraints it was enqueued with, so only a restart makes
                // the new rule take effect.
                scheduler.restart()
            }
        }

    // ---- storage location -------------------------------------------------------------------------

    @Test
    fun `the picker sees every mounted volume, which is active, and what stands in the way`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.DOWNLOADED)))
            selectedVolumeId.value = CARD_ID

            val locations = repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().first()

            locations.volumes.map { it.id } shouldBe listOf(PRIMARY_ID, CARD_ID)
            locations.activeVolumeId shouldBe CARD_ID
            locations.selectedVolumeMissing shouldBe false
            // What the confirmation dialog counts, and what the switch guard enforces.
            locations.downloadCount shouldBe 1
        }

    @Test
    fun `an ejected card is reported as a fallback rather than shown as the selection`() =
        runTest {
            every { downloadDao.observeProgress() } returns flowOf(emptyList())
            selectedVolumeId.value = "a-card-that-is-not-here"

            val locations = repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().first()

            locations.activeVolumeId shouldBe PRIMARY_ID
            locations.selectedVolumeMissing shouldBe true
        }

    @Test
    fun `switching volume with nothing downloaded takes effect at once`() =
        runTest {
            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { locations.select(CARD_ID) }
            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `switching volume while downloads exist is refused unless the caller agrees to lose them`() =
        runTest {
            // docs/PLAN.md's v1 policy: nothing moves files yet, and a finished download's file
            // rows hold absolute paths on the old volume that nothing rewrites.
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1))

            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Failure>()
            coVerify(exactly = 0) { locations.select(any()) }
            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `delete-all-and-switch empties the device before the root moves`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1), uuid(2))

            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = true)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerifyOrder {
                // Stop first so the downloader cannot hold a handle to a file being unlinked, and
                // delete before the root moves or the cascade looks on the wrong volume for them.
                scheduler.stop()
                deleter.delete(uuid(1))
                deleter.delete(uuid(2))
                locations.select(CARD_ID)
            }
        }

    @Test
    fun `re-affirming the volume already in force keeps the downloads that are on it`() =
        runTest {
            // What clearing a stale choice looks like: the card is out, the fallback is already
            // writing to internal storage, and the user taps it. Nothing moves, so nothing is lost.
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1))
            every { locations.activeVolume() } returns PRIMARY_VOLUME

            val result = repository().setStorageLocation(PRIMARY_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { locations.select(PRIMARY_ID) }
            coVerify(exactly = 0) { deleter.delete(any()) }
        }

    @Test
    fun `switching to a volume that is not mounted changes nothing`() =
        runTest {
            val result = repository().setStorageLocation("a-card-that-is-not-here", deleteExistingDownloads = true)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { locations.select(any()) }
        }

    @Test
    fun `the storage header re-reads when the volume changes, not only when a download does`() =
        runTest {
            // Switching with an empty queue changes no download row, so a projection keyed only on
            // progress would keep reporting the old volume's free space.
            every { downloadDao.observeProgress() } returns MutableStateFlow(emptyList())
            every { storage.usedBytes() } returnsMany listOf(10L, 20L)
            every { storage.availableBytes() } returnsMany listOf(90L, 180L)

            val emissions =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().take(2).toList()
                }
            selectedVolumeId.value = CARD_ID

            emissions.await().map { it.usedBytes } shouldBe listOf(10L, 20L)
        }

    // ---- what the storage walk is keyed on ---------------------------------------------------------

    @Test
    fun `progress writes alone do not re-walk the downloads tree`() =
        runTest {
            // `usedBytes()` is a stat() of every file under the root; a transfer writes progress
            // twice a second for its whole length, and keying the walk on that was PERF-02.
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()
                walks shouldBe 1

                repeat(PROGRESS_WRITES) { written ->
                    rows.value =
                        listOf(
                            progress(
                                uuid(1),
                                DownloadStatus.DOWNLOADING,
                                downloaded = (written + 1) * 1_000L,
                                total = 100_000L,
                            ),
                        )
                }

                // Same items, same statuses: nothing that changes which files are on disk.
                walks shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a status change re-walks, because that is what adds and removes files`() =
        runTest {
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()

                rows.value = listOf(progress(uuid(1), DownloadStatus.DOWNLOADED))
                walks shouldBe 2

                rows.value = emptyList()
                walks shouldBe 3
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a long transfer still creeps, on a slow tick rather than on every write`() =
        runTest {
            // The file grows without any row's *shape* changing, so the header would otherwise sit
            // still for the length of an episode.
            every { downloadDao.observeProgress() } returns
                MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()
                walks shouldBe 1

                testScheduler.advanceTimeBy(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL * TICKS + 1.milliseconds)

                walks shouldBe 1 + TICKS
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `nothing downloading means no ticking either`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADED)))
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()

                testScheduler.advanceTimeBy(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL * TICKS + 1.milliseconds)

                walks shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** Two mounted volumes; anything else the caller asks for falls back to the primary one. */
    private fun selectionFor(selectedVolumeId: String?): StorageSelection {
        val volumes = listOf(PRIMARY_VOLUME, CARD_VOLUME)
        val chosen = volumes.firstOrNull { it.id == selectedVolumeId }
        return StorageSelection(
            volumes = volumes,
            active = chosen ?: PRIMARY_VOLUME,
            selectionMissing = selectedVolumeId != null && chosen == null,
        )
    }

    // The dispatcher is a parameter only so the storage-location tests can share `runTest`'s
    // scheduler: they collect a projection that never completes, which needs the two in step.
    private fun repository(ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) =
        DownloadRepositoryImpl(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            enqueuer = enqueuer,
            deleter = deleter,
            scheduler = scheduler,
            storage = storage,
            locations = locations,
            preferences = preferences,
            sessionRepository = sessionRepository,
            clock = clock,
            ioDispatcher = ioDispatcher,
        )

    private fun progress(
        itemId: java.util.UUID,
        status: DownloadStatus,
        downloaded: Long = 0L,
        total: Long = 0L,
    ) = DownloadProgress(itemId = itemId, status = status, bytesDownloaded = downloaded, bytesTotal = total)

    private companion object {
        const val PRIMARY_ID = DownloadVolume.PRIMARY_ID
        const val CARD_ID = "1A2B-3C4D"

        /** Ten seconds of a real transfer at the throttle's two writes a second. */
        const val PROGRESS_WRITES = 20

        /** How many walk intervals the ticking tests step over. */
        const val TICKS = 3

        val PRIMARY_VOLUME =
            DownloadVolume(
                id = PRIMARY_ID,
                isPrimary = true,
                isRemovable = false,
                description = "Internal shared storage",
                directory = File("/storage/emulated/0/Android/data/app/files"),
            )

        val CARD_VOLUME =
            DownloadVolume(
                id = CARD_ID,
                isPrimary = false,
                isRemovable = true,
                description = "SD card",
                directory = File("/storage/$CARD_ID/Android/data/app/files"),
            )

        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = uuid(50),
                userId = uuid(99),
                userName = "casey",
                serverName = "home",
                serverVersion = "10.11.0",
            )

        val ITEM_ROW =
            ItemEntity(
                id = uuid(1),
                name = "Arrival",
                sortName = "Arrival",
                type = ItemType.MOVIE,
                source = ItemSource.DOWNLOAD,
                cachedAt = NOW,
                dto = "{}",
            )

        val MOVIE = JellyfinItem(id = uuid(1).toString(), name = "Arrival", type = ItemType.MOVIE)
    }
}
