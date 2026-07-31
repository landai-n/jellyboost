package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.DownloadUrlFactory
import dev.jellyboost.data.downloads.storage.DownloadStorage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/**
 * Unit tests for what [DownloadQueue] does when an item *does not* finish.
 *
 * Separate from [DownloadQueueTest], which owns the transfer itself, because these three rules were
 * all added together and are all about the same question — whether a failure is the item's fault or
 * the moment's (docs/notes/audit-2026-07.md, STAB-01, STAB-04 and STAB-09).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueRetryTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()
    private val storage = mockk<DownloadStorage>()
    private val downloader = mockk<FileDownloader>()
    private val seeder = mockk<SiblingSeeder>(relaxUnitFun = true)
    private val sweeper = mockk<OrphanSweeper>()
    private val urls = mockk<DownloadUrlFactory>(relaxed = true)
    private val sessionGate = mockk<SessionGate>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val listener = RecordingListener()

    private var nextFileId = 1L

    @BeforeEach
    fun setUp() {
        every { storage.prepareItemDirectory(any()) } returns File("/tmp/downloads")
        every { storage.resolve(any(), any()) } answers { File("/tmp/downloads/${secondArg<String>()}") }
        coEvery { downloadDao.insertFile(any()) } answers { nextFileId++ }
        coEvery { downloadDao.get(any()) } returns download()
        coEvery { itemDao.getItem(any()) } returns ITEM_ENTITY
        every { itemMapper.toDtoOrNull(any()) } returns movie()
        every { urls.mediaUrl(any()) } returns "https://server/download"
        every { urls.imageUrl(any(), any(), any(), any()) } returns "https://server/image"
        coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { seeder.seedFor(any(), any(), any(), any(), any()) } returns null
        coEvery { sweeper.sweep() } returns 0L
    }

    // ---- the retry policy (STAB-01) ---------------------------------------------------------------

    @Test
    fun `a transient failure keeps the row queued instead of failing it`() =
        runTest {
            // The finding, in one test: a proxy 502 or a reset connection used to move the row to
            // ERROR under a message promising a retry that nothing performed.
            queueWith(download())
            coEvery { downloader.download(match { it.contains("download") }, any(), any(), any(), any(), any()) } throws
                IOException("connection reset")

            queue().drain(listener) shouldBe DrainOutcome.RETRY

            coVerify { downloadDao.requeueForRetry(uuid(1), 1, NOW) }
            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, any(), any()) }
        }

    @Test
    fun `a transient failure stops the drain instead of walking the rest of the queue into it`() =
        runTest {
            // The cascade the finding describes: whatever made the first item fail is about to do
            // the same to the thirty-nine behind it, and the drain loop used to give it the chance
            // within seconds. Nothing after the first failure is even attempted.
            val first = download(itemId = uuid(1))
            val second = download(itemId = uuid(2), queuePosition = 1)
            coEvery { downloadDao.nextRunnable() } returnsMany
                listOf(withFiles(first), withFiles(second), null)
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws IOException("502")

            queue().drain(listener) shouldBe DrainOutcome.RETRY

            coVerify(exactly = 0) { downloadDao.setStatus(uuid(2), DownloadStatus.DOWNLOADING, any(), any()) }
            coVerify(exactly = 0) { downloadDao.requeueForRetry(uuid(2), any(), any()) }
        }

    @Test
    fun `a retried row that succeeds next time ends up DOWNLOADED`() =
        runTest {
            // Two drains, as WorkManager would run them: the first meets a server that is
            // restarting, the second meets one that is back. Nothing about the row's own bytes
            // changed in between, which is the point of leaving it QUEUED.
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws IOException("502")
            queue().drain(listener) shouldBe DrainOutcome.RETRY

            queueWith(download(attemptCount = 1))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `the attempt on the row is what advances, so a retry survives process death`() =
        runTest {
            // The retry is performed by a *new* worker run, in a process that may not be this one;
            // a counter in memory would hand every restart a full budget and never stop trying.
            queueWith(download(attemptCount = 2))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws IOException("502")

            queue().drain(listener)

            coVerify { downloadDao.requeueForRetry(uuid(1), 3, NOW) }
        }

    @Test
    fun `a transient failure past the cap is finally an ERROR`() =
        runTest {
            // Bounded, or a server that is simply gone keeps a foreground service alive all
            // afternoon on WorkManager's backoff.
            queueWith(download(attemptCount = DownloadQueue.MAX_ATTEMPTS - 1))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws IOException("502")

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, NOW, any()) }
            coVerify(exactly = 0) { downloadDao.requeueForRetry(any(), any(), any()) }
        }

    @Test
    fun `a permanent failure is an ERROR on the first attempt, with no budget spent`() =
        runTest {
            // A 404 is not going to become a 200 in thirty seconds, and the drain carries on to the
            // next item rather than stopping — one missing film must not park a whole queue.
            val first = download(itemId = uuid(1))
            val second = download(itemId = uuid(2), queuePosition = 1)
            coEvery { downloadDao.nextRunnable() } returnsMany
                listOf(withFiles(first), withFiles(second), null)
            coEvery { downloader.download(match { it.contains("download") }, any(), any(), any(), any(), any()) } throws
                DownloadHttpException(code = 404, url = "https://server/download")

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify(exactly = 0) { downloadDao.requeueForRetry(any(), any(), any()) }
            coVerify {
                downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, NOW, "This item is no longer on the server.")
            }
            coVerify { downloadDao.setStatus(uuid(2), DownloadStatus.ERROR, NOW, any()) }
        }

    @Test
    fun `an item whose metadata is gone is failed at once rather than retried forever`() =
        runTest {
            queueWith(download())
            coEvery { itemDao.getItem(any()) } returns null

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify(exactly = 0) { downloadDao.requeueForRetry(any(), any(), any()) }
        }

    @Test
    fun `a cancellation is never counted as an attempt`() =
        runTest {
            // Pause is the commonest cancellation in the app. Spending a retry on it would mean a
            // download the user paused five times could never be resumed again.
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("paused")

            assertThrows<CancellationException> { queue().drain(listener) }

            coVerify(exactly = 0) { downloadDao.requeueForRetry(any(), any(), any()) }
            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, any(), any()) }
        }

    // ---- the drain lease (STAB-09) ----------------------------------------------------------------

    @Test
    fun `two drains never overlap, so one file never has two writers`() =
        runTest {
            // A `REPLACE` enqueue starts the new worker while the old one is still unwinding, and
            // both would run `requeueInterrupted` — the second claiming the row the first still
            // holds a RandomAccessFile on. The second drain waits instead.
            val released = CompletableDeferred<Unit>()
            var overlapped = false
            var inFlight = false
            queueWith(download(itemId = uuid(1)), download(itemId = uuid(2), queuePosition = 1))
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } coAnswers {
                if (inFlight) overlapped = true
                inFlight = true
                released.await()
                inFlight = false
                100L
            }

            val subject = queue()
            val first = async { subject.drain(listener) }
            val second = async { subject.drain(listener) }
            runCurrent()
            released.complete(Unit)
            first.await()
            second.await()

            overlapped shouldBe false
        }

    @Test
    fun `a cancelled drain hands the lease on rather than keeping it`() =
        runTest {
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("replaced")

            val subject = queue()
            runCatching { subject.drain(listener) }
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L

            subject.drain(listener) shouldBe DrainOutcome.COMPLETED
        }

    // ---- the orphan sweep (STAB-04) ---------------------------------------------------------------

    @Test
    fun `every drain starts by sweeping the directories no row claims`() =
        runTest {
            // The bytes a cancel-during-transfer leaves behind are invisible to the UI and counted
            // by the storage header; the head of a drain is the one moment nothing is writing.
            queueWith(download())

            queue().drain(listener)

            coVerify(exactly = 1) { sweeper.sweep() }
        }

    @Test
    fun `a parked queue sweeps too, since orphans outlive a missing session`() =
        runTest {
            coEvery { sessionGate.ensureSession() } returns false
            queueWith(download())

            queue().drain(listener) shouldBe DrainOutcome.NO_SESSION

            coVerify(exactly = 1) { sweeper.sweep() }
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun queue() =
        DownloadQueue(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            planner = DownloadFilePlanner(urls),
            storage = storage,
            downloader = downloader,
            // No transcoded row here reaches an audio sidecar; the strip stage is exercised in
            // [DownloadQueueTest].
            extractor = mockk(relaxed = true),
            seeder = seeder,
            sweeper = sweeper,
            sessionGate = sessionGate,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun queueWith(vararg downloads: DownloadEntity) {
        val queued = downloads.map(::withFiles)
        coEvery { downloadDao.nextRunnable() } returnsMany (queued + null)
    }

    private fun withFiles(download: DownloadEntity) =
        DownloadWithFiles(download = download, files = emptyList<DownloadFileEntity>())

    private class RecordingListener : DownloadQueueListener {
        override suspend fun onProgress(
            download: DownloadEntity,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ) = Unit

        override suspend fun onIdle() = Unit
    }

    private companion object {
        val ITEM_ENTITY =
            ItemEntity(
                id = uuid(1),
                name = "Arrival",
                sortName = "Arrival",
                type = ItemType.MOVIE,
                source = ItemSource.DOWNLOAD,
                cachedAt = NOW,
                dto = "{}",
            )
    }
}
