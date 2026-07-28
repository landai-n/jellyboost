package dev.jellyfinnative.data.downloads

import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
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
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import dev.jellyfinnative.data.downloads.work.DownloadScheduler
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

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
    private val preferences = mockk<AppPreferences>(relaxUnitFun = true)
    private val sessionRepository = mockk<SessionRepository>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns MutableStateFlow(LOGGED_IN)
        every { preferences.downloadOverWifiOnly } returns flowOf(true)
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
            coEvery { enqueuer.enqueue(uuid(1), uuid(99)) } returns AppResult.Success(download())

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
    fun `resuming re-queues the row and restarts with fresh constraints`() =
        runTest {
            repository().resume(uuid(1).toString())

            coVerifyOrder {
                downloadDao.setStatus(uuid(1), DownloadStatus.QUEUED, NOW, null)
                scheduler.restart()
            }
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

    // ---- helpers --------------------------------------------------------------------------------

    private fun repository() =
        DownloadRepositoryImpl(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            enqueuer = enqueuer,
            deleter = deleter,
            scheduler = scheduler,
            storage = storage,
            preferences = preferences,
            sessionRepository = sessionRepository,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun progress(
        itemId: java.util.UUID,
        status: DownloadStatus,
        downloaded: Long = 0L,
        total: Long = 0L,
    ) = DownloadProgress(itemId = itemId, status = status, bytesDownloaded = downloaded, bytesTotal = total)

    private companion object {
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
