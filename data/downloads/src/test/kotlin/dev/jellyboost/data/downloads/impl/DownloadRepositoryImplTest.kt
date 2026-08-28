package dev.jellyboost.data.downloads.impl

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.file
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.model.SizeCertainty
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.DownloadVolume
import dev.jellyboost.data.downloads.storage.StorageLocationManager
import dev.jellyboost.data.downloads.storage.StorageSelection
import dev.jellyboost.data.downloads.work.DownloadScheduler
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/**
 * Two things are pinned here that nothing else covers: the `DownloadStatus` → `DownloadState` mapping
 * every badge in the app renders from, and the ordering rules of the mutations (stop the queue *before*
 * unlinking files, restart it after).
 */
@OptIn(ExperimentalCoroutinesApi::class)
// The repository's whole surface is one class under one mock harness; splitting the cases across files
// would duplicate that harness rather than shorten anything.
@Suppress("LargeClass")
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
        coEvery { deleter.deleteAll(any()) } returns 0L
        coEvery { downloadDao.pending() } returns emptyList()
        coEvery { downloadDao.unfinished() } returns emptyList()
        coEvery { downloadDao.get(any()) } returns null
        // The demote transaction answers "was the live transfer among the rows I took?"; most tests act
        // on rows the worker is not on, so the default answer is no.
        coEvery { downloadDao.demoteRunnable(any(), any(), any()) } returns false
        givenCachedItems()
    }

    private fun givenTransferring(vararg ids: java.util.UUID) {
        coEvery { downloadDao.pending() } returns
            ids.map { download(itemId = it, status = DownloadStatus.DOWNLOADING) }
    }

    /**
     * Makes the demote transaction report that [ids]' batch included the row being transferred — the
     * answer `pause`/`delete` act on *instead of* a separate read, so no drain claim can land between
     * deciding and writing.
     */
    private fun givenDemoteTakesLiveTransfer(ids: List<java.util.UUID>) {
        coEvery { downloadDao.demoteRunnable(ids, any(), NOW) } returns true
    }

    /**
     * Stands the two item reads the download list makes up over one set of rows: `getCacheKeys` says
     * which blobs are still current and `getItems` hands over only the ones that are not.
     */
    private fun givenCachedItems(vararg rows: ItemEntity) {
        coEvery { itemDao.getCacheKeys(any()) } answers {
            val ids = firstArg<List<java.util.UUID>>().toSet()
            rows.filter { it.id in ids }.map { ItemCacheKey(it.id, it.source, it.cachedAt) }
        }
        coEvery { itemDao.getItems(any()) } answers {
            val ids = firstArg<List<java.util.UUID>>().toSet()
            rows.filter { it.id in ids }
        }
    }

    // ---- badge states ---------------------------------------------------------------------------

    // `observeStates()` shares its Room subscription over `@ApplicationScope`, so these four tests run
    // on an `UnconfinedTestDispatcher`: unconfined dispatch is what lets a `backgroundScope` collector
    // actually run the sharing coroutine `WhileSubscribed` starts, with no `advanceUntilIdle()` needed.
    // Turbine is deliberately not used: its `awaitItem()` is bound by a *wall-clock* timeout, which a
    // virtual-time suite cannot wait out.

    @Test
    fun `each status maps onto the badge state the UI draws`() =
        runTest(UnconfinedTestDispatcher()) {
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

            val collected = mutableListOf<Map<String, DownloadState>>()
            backgroundScope.launch { repository().observeStates().collect { collected.add(it) } }

            collected.last() shouldContainExactly
                mapOf(
                    uuid(1).toString() to DownloadState.Queued,
                    uuid(2).toString() to DownloadState.Downloading(progress = 0.25f),
                    uuid(3).toString() to DownloadState.Paused,
                    uuid(4).toString() to DownloadState.Downloaded,
                    uuid(5).toString() to DownloadState.Failed,
                )
        }

    @Test
    fun `a cancelled row reads as not downloaded`() =
        runTest(UnconfinedTestDispatcher()) {
            // CANCELLED only exists between a cancel and the row's deletion; a badge for it would be a
            // badge for a state the user already asked to be rid of.
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.CANCELLED)))

            val collected = mutableListOf<Map<String, DownloadState>>()
            backgroundScope.launch { repository().observeStates().collect { collected.add(it) } }

            collected.last()[uuid(1).toString()] shouldBe DownloadState.NotDownloaded
        }

    @Test
    fun `a download with no known size reports zero progress rather than complete`() =
        runTest(UnconfinedTestDispatcher()) {
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING, downloaded = 500L, total = 0L)))

            val collected = mutableListOf<Map<String, DownloadState>>()
            backgroundScope.launch { repository().observeStates().collect { collected.add(it) } }

            collected.last()[uuid(1).toString()] shouldBe DownloadState.Downloading(progress = 0f)
        }

    @Test
    fun `two callers of observeStates share one Room subscription`() =
        runTest(UnconfinedTestDispatcher()) {
            // A cold flow per call would mean four ViewModels each holding an independent
            // `observeProgress()` collector doing the same map over the same rows. The repository holds
            // one instead, shared no matter how many callers ask for it.
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.QUEUED)))

            val repo = repository()

            backgroundScope.launch { repo.observeStates().collect {} }
            backgroundScope.launch { repo.observeStates().collect {} }

            verify(exactly = 1) { downloadDao.observeProgress() }
        }

    // ---- one item's footprint on disk (detail screen "N on device") -------------------------------

    @Test
    fun `a valid id delegates straight to the DAO's projection`() =
        runTest {
            every { downloadDao.observeBytesOnDisk(uuid(1)) } returns flowOf(900L)

            repository().observeBytesOnDisk(uuid(1).toString()).test {
                awaitItem() shouldBe 900L
                awaitComplete()
            }
        }

    @Test
    fun `an unparseable id reports no footprint rather than throwing`() =
        runTest {
            repository().observeBytesOnDisk("not-a-uuid").test {
                awaitItem().shouldBeNull()
                awaitComplete()
            }
        }

    // ---- the download list ----------------------------------------------------------------------

    @Test
    fun `a download row is joined to its cached item`() =
        runTest {
            every { downloadDao.observeAll() } returns
                flowOf(listOf(DownloadWithFiles(download(), listOf(file(id = 1, bytesDownloaded = 900L)))))
            givenCachedItems(ITEM_ROW)
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
    fun `a track's grouping columns reach the row that draws its heading`() =
        runTest {
            val track =
                download(
                    itemType = ItemType.AUDIO,
                    albumName = "Rumours",
                    artistName = "Fleetwood Mac",
                    groupId = uuid(40),
                )
            every { downloadDao.observeAll() } returns flowOf(listOf(DownloadWithFiles(track, emptyList())))

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.albumName shouldBe "Rumours"
                row.artistLine shouldBe "Fleetwood Mac"
                row.groupId shouldBe uuid(40)
                awaitComplete()
            }
        }

    @Test
    fun `an Original download's row carries the exact quality the server reported`() =
        runTest {
            // The UI's wording is decided from `quality`, `sizeIsExact` and `projectedBytes` together —
            // each must survive the Room round trip untouched.
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
            givenCachedItems()

            repository().observeDownloads().test {
                val row = awaitItem().single()
                row.item shouldBe null
                // The denormalised title is why the row is still usable.
                row.title shouldBe "Arrival"
                awaitComplete()
            }
        }

    // ---- what a progress write costs -------------------------------------------------------------

    @Test
    fun `a progress write does not re-parse the metadata it did not change`() =
        runTest {
            // `SELECT *` per emission would decode each downloaded item's whole BaseItemDto — tens of
            // kilobytes apiece — and a transfer writes progress twice a second for its whole length.
            val rows = MutableStateFlow(listOf(row(bytesOnDisk = 0L)))
            every { downloadDao.observeAll() } returns rows
            givenCachedItems(ITEM_ROW)
            every { itemMapper.toDomainOrNull(ITEM_ROW, null) } returns MOVIE

            repository(UnconfinedTestDispatcher(testScheduler)).observeDownloads().test {
                awaitItem()

                repeat(PROGRESS_WRITES) { written ->
                    rows.value = listOf(row(bytesOnDisk = (written + 1) * 1_000L))
                    awaitItem()
                }

                verify(exactly = 1) { itemMapper.toDomainOrNull(ITEM_ROW, null) }
                coVerify(exactly = 1) { itemDao.getItems(any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a rewritten item row is read again`() =
        runTest {
            // `cachedAt` is bumped by every write to an items row, which is exactly when the memoised
            // metadata stops describing what is stored.
            val refreshed = ITEM_ROW.copy(name = "Arrival (remastered)", cachedAt = NOW.plusSeconds(60))
            val rows = MutableStateFlow(listOf(row(bytesOnDisk = 0L)))
            every { downloadDao.observeAll() } returns rows
            givenCachedItems(ITEM_ROW)
            every { itemMapper.toDomainOrNull(any(), null) } returns MOVIE

            repository(UnconfinedTestDispatcher(testScheduler)).observeDownloads().test {
                awaitItem()

                givenCachedItems(refreshed)
                rows.value = listOf(row(bytesOnDisk = 1_000L))
                awaitItem()

                verify(exactly = 1) { itemMapper.toDomainOrNull(refreshed, null) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an item that leaves the list does not keep its metadata alive`() =
        runTest {
            val rows = MutableStateFlow(listOf(row(bytesOnDisk = 0L)))
            every { downloadDao.observeAll() } returns rows
            givenCachedItems(ITEM_ROW)
            every { itemMapper.toDomainOrNull(ITEM_ROW, null) } returns MOVIE

            repository(UnconfinedTestDispatcher(testScheduler)).observeDownloads().test {
                awaitItem()

                rows.value = emptyList()
                awaitItem()
                rows.value = listOf(row(bytesOnDisk = 0L))
                awaitItem()

                // Deleted and downloaded again is a different file; nothing about the old one may be
                // assumed to still hold.
                verify(exactly = 2) { itemMapper.toDomainOrNull(ITEM_ROW, null) }
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun row(bytesOnDisk: Long) =
        DownloadWithFiles(
            download(status = DownloadStatus.DOWNLOADING),
            listOf(file(id = 1, bytesDownloaded = bytesOnDisk)),
        )

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
            // idempotent and drains whatever Room holds.
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
    fun `pausing the item that is transferring interrupts the running job`() =
        runTest {
            givenDemoteTakesLiveTransfer(listOf(uuid(1)))

            repository().pause(uuid(1).toString())

            coVerifyOrder {
                downloadDao.demoteRunnable(listOf(uuid(1)), DownloadStatus.PAUSED, NOW)
                // Cancelling the work is the only way to interrupt a transfer already in flight; the
                // restart then picks up whatever is left.
                scheduler.stop()
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `pausing a row that is not transferring leaves the running transfer alone`() =
        runTest {
            // Stopping the worker for a row it is not even on cancels whatever it *is* on — and a
            // cancelled transcode restarts from byte zero.
            repository().pause(uuid(1).toString())

            coVerify(exactly = 1) { downloadDao.demoteRunnable(listOf(uuid(1)), DownloadStatus.PAUSED, NOW) }
            coVerify(exactly = 0) { scheduler.stop() }
        }

    @Test
    fun `pausing decides and writes in one transaction, not a read then a write`() =
        runTest {
            // A separate "is it transferring?" read before an unguarded PAUSED write would let the
            // drain's claim land between the two — the read says no, the claim takes the row, and the
            // item the user just paused downloads to completion. The stop decision must come back from
            // the very transaction that writes PAUSED.
            repository().pause(uuid(1).toString())

            coVerify(exactly = 0) { downloadDao.pending() }
            coVerify(exactly = 0) { downloadDao.setStatus(any(), any(), any(), any()) }
        }

    @Test
    fun `pausing writes PAUSED and leaves the rest of the queue running`() =
        runTest {
            // The repository's own writes must leave exactly one status behind, and the queue must be
            // brought back up with `ensureRunning` so items behind the paused one keep draining.
            givenDemoteTakesLiveTransfer(listOf(uuid(1)))

            repository().pause(uuid(1).toString())

            coVerify(exactly = 1) { downloadDao.demoteRunnable(listOf(uuid(1)), DownloadStatus.PAUSED, NOW) }
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
    fun `resuming while another item is transferring joins the live drain instead of restarting it`() =
        runTest {
            // A restart cancels the running worker; mid-transcode that discards the transfer so far. The
            // live drain picks the re-queued row up from nextRunnable() itself.
            givenTransferring(uuid(9))

            repository().resume(uuid(1).toString())

            coVerify(exactly = 0) { scheduler.restart() }
            coVerify(exactly = 1) { scheduler.ensureRunning() }
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
    fun `deleting the transferring item stops the queue before unlinking files`() =
        runTest {
            givenDemoteTakesLiveTransfer(listOf(uuid(1)))
            coEvery { deleter.deleteAll(listOf(uuid(1))) } returns 2_100_000_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(2_100_000_000L)

            // The downloader must not be holding a handle to a file we are about to remove — and the row
            // is flipped out of the queue's reach in the same transaction that says the worker was on
            // it, so a drain claim cannot slip in after the decision.
            coVerifyOrder {
                downloadDao.demoteRunnable(listOf(uuid(1)), DownloadStatus.CANCELLED, NOW)
                scheduler.stop()
                deleter.deleteAll(listOf(uuid(1)))
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `cancelling the item that is downloading runs the same cascade as a delete`() =
        runTest {
            // *Cancel* in the Queue tab, *Delete* in the Downloaded list and *Cancel* on the notification
            // are one operation, and an in-flight item is no exception: its files must not survive the row.
            givenDemoteTakesLiveTransfer(listOf(uuid(1)))
            coEvery { deleter.deleteAll(listOf(uuid(1))) } returns 1_400_000_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(1_400_000_000L)

            coVerifyOrder {
                scheduler.stop()
                deleter.deleteAll(listOf(uuid(1)))
                // Something else may still be queued behind the cancelled item.
                scheduler.ensureRunning()
            }
        }

    @Test
    fun `cancelling a merely queued item runs the cascade without touching the live transfer`() =
        runTest {
            // A queued item can still have bytes on disk: it was interrupted mid-transfer and put back in
            // the queue. The worker, though, is on some *other* row — stopping it here would cancel an
            // unrelated transcode.
            coEvery { deleter.deleteAll(listOf(uuid(1))) } returns 900_000L

            repository().delete(uuid(1).toString()) shouldBe AppResult.Success(900_000L)

            // The row is still taken out of the queue's reach before the unlink: CANCELLED is what makes
            // a drain claim arriving *after* this decision refuse the row.
            coVerify(exactly = 1) { downloadDao.demoteRunnable(listOf(uuid(1)), DownloadStatus.CANCELLED, NOW) }
            coVerify(exactly = 1) { deleter.deleteAll(listOf(uuid(1))) }
            coVerify(exactly = 0) { scheduler.stop() }
        }

    @Test
    fun `a failing delete is a Storage failure, not an exception`() =
        runTest {
            coEvery { deleter.deleteAll(any()) } throws IllegalStateException("volume ejected")

            val result = repository().delete(uuid(1).toString())

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- the awaited stop ------------------------------------------------------------------------

    @Test
    fun `nothing is unlinked until the queue has actually stopped`() =
        runTest {
            // WorkManager's cancellation is asynchronous, and `FileDownloader` re-creates the item
            // directory for every file it opens: a delete that runs while the transfer is still alive
            // gets its directory put straight back by a `mkdirs()`. Those bytes are then invisible.
            givenDemoteTakesLiveTransfer(listOf(uuid(1)))
            val stopped = CompletableDeferred<Unit>()
            coEvery { scheduler.stop() } coAnswers { stopped.await() }

            val repository = repository(ioDispatcher = StandardTestDispatcher(testScheduler))
            val delete = async { repository.delete(uuid(1).toString()) }
            runCurrent()
            coVerify(exactly = 0) { deleter.deleteAll(any()) }

            stopped.complete(Unit)
            delete.await()

            coVerify(exactly = 1) { deleter.deleteAll(listOf(uuid(1))) }
        }

    // ---- bulk actions ----------------------------------------------------------------------------

    @Test
    fun `pausing everything is one status write and one restart, not one per row`() =
        runTest {
            // A bulk action built out of single-item mutations would issue a stop/start cycle per row, so
            // a forty-episode queue would produce forty overlapping drains — each running
            // `requeueInterrupted` over rows another drain is still writing.
            val ids = listOf(uuid(1), uuid(2), uuid(3))
            givenDemoteTakesLiveTransfer(ids)

            repository().pauseAll(ids.map(java.util.UUID::toString)) shouldBe AppResult.Success(Unit)

            coVerifyOrder {
                downloadDao.demoteRunnable(ids, DownloadStatus.PAUSED, NOW)
                scheduler.stop()
                scheduler.ensureRunning()
            }
            coVerify(exactly = 1) { scheduler.stop() }
            coVerify(exactly = 1) { scheduler.ensureRunning() }
            coVerify(exactly = 0) { downloadDao.setStatus(any(), any(), any(), any()) }
        }

    @Test
    fun `pause all keeps the transcode it deliberately excluded from the batch`() =
        runTest {
            // The batch the UI passes here excludes the running transcode, so the worker must not be
            // stopped or the encode is cancelled anyway and restarts from byte zero. The demote
            // transaction reports the live transfer untouched, and that answer — atomic with the PAUSED
            // write itself, unlike a separate read — is what spares the worker.
            val pausable = listOf(uuid(1), uuid(2))

            repository().pauseAll(pausable.map(java.util.UUID::toString)) shouldBe AppResult.Success(Unit)

            coVerify(exactly = 1) { downloadDao.demoteRunnable(pausable, DownloadStatus.PAUSED, NOW) }
            coVerify(exactly = 0) { scheduler.stop() }
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
    fun `resuming everything while a transfer is live joins it rather than restarting it`() =
        runTest {
            givenTransferring(uuid(9))
            val ids = listOf(uuid(1), uuid(2))

            repository().resumeAll(ids.map(java.util.UUID::toString)) shouldBe AppResult.Success(Unit)

            coVerify(exactly = 0) { scheduler.restart() }
            coVerify(exactly = 1) { scheduler.ensureRunning() }
        }

    @Test
    fun `cancelling everything stops the queue once and starts it once`() =
        runTest {
            val ids = listOf(uuid(1), uuid(2))
            givenDemoteTakesLiveTransfer(ids)
            coEvery { deleter.deleteAll(ids) } returns 300L

            repository().deleteAll(ids.map(java.util.UUID::toString)) shouldBe
                AppResult.Success(300L)

            coVerifyOrder {
                downloadDao.demoteRunnable(ids, DownloadStatus.CANCELLED, NOW)
                scheduler.stop()
                deleter.deleteAll(ids)
                scheduler.ensureRunning()
            }
            coVerify(exactly = 1) { scheduler.stop() }
        }

    @Test
    fun `a bulk action with no targets left touches neither Room nor the scheduler`() =
        runTest {
            // The queue can drain while the user is reaching for the button; stopping and restarting the
            // worker for an empty list would interrupt whatever took its place.
            repository().pauseAll(emptyList()) shouldBe AppResult.Success(Unit)
            repository().resumeAll(emptyList()) shouldBe AppResult.Success(Unit)
            repository().deleteAll(emptyList()) shouldBe AppResult.Success(0L)

            coVerify(exactly = 0) { downloadDao.demoteRunnable(any(), any(), any()) }
            coVerify(exactly = 0) { scheduler.stop() }
            coVerify(exactly = 0) { scheduler.restart() }
            coVerify(exactly = 0) { deleter.deleteAll(any()) }
        }

    @Test
    fun `an unparseable id in a bulk action is a NotFound, and nothing is changed`() =
        runTest {
            val result = repository().pauseAll(listOf(uuid(1).toString(), "not-a-uuid"))

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { downloadDao.demoteRunnable(any(), any(), any()) }
            coVerify(exactly = 0) { scheduler.stop() }
        }

    @Test
    fun `a failing bulk delete is a Storage failure, not an exception`() =
        runTest {
            coEvery { deleter.deleteAll(any()) } throws IllegalStateException("volume ejected")

            val result = repository().deleteAll(listOf(uuid(1).toString()))

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- reordering -----------------------------------------------------------------------------

    @Test
    fun `moving an item renumbers the queue from zero`() =
        runTest {
            val moved = download(itemId = uuid(3), queuePosition = 7)
            coEvery { downloadDao.unfinished() } returns
                listOf(
                    download(itemId = uuid(1), queuePosition = 2),
                    download(itemId = uuid(2), queuePosition = 5),
                    moved,
                )
            coEvery { downloadDao.get(uuid(3)) } returns moved

            repository().move(uuid(3).toString(), targetItemId = uuid(1).toString())

            // Gaps left by completed items would otherwise make "position" mean something other than
            // "place in the list".
            coVerify { downloadDao.setQueuePosition(uuid(3), 0, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(1), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(2), 2, NOW) }
        }

    @Test
    fun `a row moved onto the last item lands at the end`() =
        runTest {
            val moved = download(itemId = uuid(1), queuePosition = 0)
            coEvery { downloadDao.unfinished() } returns listOf(moved, download(itemId = uuid(2), queuePosition = 1))
            coEvery { downloadDao.get(uuid(1)) } returns moved

            repository().move(uuid(1).toString(), targetItemId = uuid(2).toString())

            coVerify { downloadDao.setQueuePosition(uuid(1), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(2), 0, NOW) }
        }

    @Test
    fun `the reorder renumbers the list the user sees, not the shorter one the engine reads`() =
        runTest {
            // Renumbering `pending()` left the failed row holding its old position while the rows
            // around it were renumbered from zero, so it drifted past them for a move it took no part
            // in. `unfinished()` is exactly the list the queue tab draws.
            val moved = download(itemId = uuid(4), queuePosition = 8)
            coEvery { downloadDao.unfinished() } returns
                listOf(
                    download(itemId = uuid(1), queuePosition = 5),
                    download(itemId = uuid(2), status = DownloadStatus.ERROR, queuePosition = 6),
                    download(itemId = uuid(3), queuePosition = 7),
                    moved,
                )
            coEvery { downloadDao.get(uuid(4)) } returns moved

            repository().move(uuid(4).toString(), targetItemId = uuid(1).toString())

            coVerify { downloadDao.setQueuePosition(uuid(4), 0, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(1), 1, NOW) }
            // The failed row is renumbered with the rest, so it keeps its place between them.
            coVerify { downloadDao.setQueuePosition(uuid(2), 2, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(3), 3, NOW) }
        }

    @Test
    fun `a failed row above the target does not turn the move into a no-op`() =
        runTest {
            // The trace: [failed, A, B], move B up onto A. Taking B's neighbour as an *index* into a
            // list that omits the failed row named A's slot in a shorter list, and B was reinserted
            // exactly where it already sat.
            val moved = download(itemId = uuid(3), queuePosition = 7)
            coEvery { downloadDao.unfinished() } returns
                listOf(
                    download(itemId = uuid(1), status = DownloadStatus.ERROR, queuePosition = 3),
                    download(itemId = uuid(2), queuePosition = 5),
                    moved,
                )
            coEvery { downloadDao.get(uuid(3)) } returns moved

            repository().move(uuid(3).toString(), targetItemId = uuid(2).toString())

            coVerify { downloadDao.setQueuePosition(uuid(1), 0, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(3), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(2), 2, NOW) }
        }

    @Test
    fun `a failed row between the movers does not send the mover to another row's place`() =
        runTest {
            // The trace: [trackA, failed film, film, trackB], move trackA down onto trackB.
            val moved = download(itemId = uuid(1), queuePosition = 0)
            coEvery { downloadDao.unfinished() } returns
                listOf(
                    moved,
                    download(itemId = uuid(2), status = DownloadStatus.ERROR, queuePosition = 1),
                    download(itemId = uuid(3), queuePosition = 2),
                    download(itemId = uuid(4), queuePosition = 3),
                )
            coEvery { downloadDao.get(uuid(1)) } returns moved

            repository().move(uuid(1).toString(), targetItemId = uuid(4).toString())

            // The mover takes the target's place, and the two films keep their own order.
            coVerify { downloadDao.setQueuePosition(uuid(2), 0, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(3), 1, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(4), 2, NOW) }
            coVerify { downloadDao.setQueuePosition(uuid(1), 3, NOW) }
        }

    @Test
    fun `a target that left the queue between the tap and the write moves nothing`() =
        runTest {
            // It finished or was deleted in between: there is no place left to take, and the row the
            // user aimed at is already gone from the list they aimed at it in.
            val moved = download(itemId = uuid(1), queuePosition = 0)
            coEvery { downloadDao.unfinished() } returns listOf(moved)
            coEvery { downloadDao.get(uuid(1)) } returns moved

            repository().move(uuid(1).toString(), targetItemId = uuid(9).toString())

            coVerify(exactly = 0) { downloadDao.setQueuePosition(any(), any(), any()) }
        }

    // ---- preferences ----------------------------------------------------------------------------

    @Test
    fun `flipping Wi-Fi only re-applies the constraint to the running queue`() =
        runTest {
            repository().setWifiOnly(false)

            coVerifyOrder {
                preferences.setDownloadOverWifiOnly(false)
                // A running job keeps the constraints it was enqueued with, so only a restart makes the
                // new rule take effect.
                scheduler.restart()
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

    // The dispatcher is a parameter so the storage-location tests can share `runTest`'s scheduler: they
    // collect a projection that never completes, which needs the two in step. The default ties to
    // `testScheduler` too, because `appScope` (`backgroundScope`) already shares it and coroutines-test
    // throws ("Detected use of different schedulers") the moment both are exercised in one hierarchy.
    //
    // `TestScope.repository` rather than a bare function: `observeStates()` is a `stateIn` shared over
    // `@ApplicationScope` and a `StateFlow` never completes on its own, so `backgroundScope` stands in
    // for that scope and is cancelled when the test ends.
    private fun TestScope.repository(ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
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
            appScope = backgroundScope,
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
