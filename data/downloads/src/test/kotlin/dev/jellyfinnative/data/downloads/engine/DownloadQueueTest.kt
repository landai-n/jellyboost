package dev.jellyfinnative.data.downloads.engine

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.network.session.SessionGate
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.download
import dev.jellyfinnative.data.downloads.DownloadFixtures.file
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.plan.DownloadFilePlanner
import dev.jellyfinnative.data.downloads.plan.DownloadUrlFactory
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/**
 * Unit tests for [DownloadQueue] — the state machine the whole milestone hangs off.
 *
 * The properties worth protecting are the ones a user notices when they break: an item that fails
 * on its poster must still be playable, an item that fails on its media file must not claim to be
 * downloaded, and a killed process must leave a row the next run can resume rather than one stuck
 * in `DOWNLOADING` forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()
    private val storage = mockk<DownloadStorage>()
    private val downloader = mockk<FileDownloader>()
    private val urls = mockk<DownloadUrlFactory>(relaxed = true)
    private val sessionGate = mockk<SessionGate>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val listener = RecordingListener()

    private var nextFileId = 1L

    /** Every value written to `downloads.projectedBytes`, in order — `null` included. */
    private val projections = mutableListOf<Long?>()

    @BeforeEach
    fun setUp() {
        coEvery { downloadDao.updateProgress(any(), any(), any(), any(), any()) } answers {
            projections += arg<Long?>(3)
        }
        every { storage.prepareItemDirectory(any()) } returns File("/tmp/downloads")
        every { storage.resolve(any(), any()) } answers { File("/tmp/downloads/${secondArg<String>()}") }
        coEvery { downloadDao.insertFile(any()) } answers { nextFileId++ }
        coEvery { downloadDao.get(any()) } returns download()
        coEvery { itemDao.getItem(any()) } returns ITEM_ENTITY
        every { itemMapper.toDtoOrNull(any()) } returns movie()
        every { urls.mediaUrl(any()) } returns "https://server/download"
        every { urls.imageUrl(any(), any(), any(), any()) } returns "https://server/image"
        coEvery { downloader.download(any(), any(), any(), any(), any()) } returns 100L
        coEvery { sessionGate.ensureSession() } returns true
    }

    // ---- the happy path -------------------------------------------------------------------------

    @Test
    fun `a drained item ends up DOWNLOADED`() =
        runTest {
            queueWith(download())

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADING, NOW, null) }
            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `interrupted rows are put back in the queue before anything else runs`() =
        runTest {
            // A row still marked DOWNLOADING belongs to a process that no longer exists; without
            // this the queue could not tell it from the item it is running right now.
            queueWith()

            queue().drain(listener)

            coVerify(exactly = 1) { downloadDao.requeueInterrupted(NOW) }
        }

    @Test
    fun `the queue keeps taking items until nothing is runnable`() =
        runTest {
            val first = download(itemId = uuid(1))
            val second = download(itemId = uuid(2), queuePosition = 1)
            coEvery { downloadDao.nextRunnable() } returnsMany
                listOf(withFiles(first), withFiles(second), null)

            queue().drain(listener)

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
            coVerify { downloadDao.setStatus(uuid(2), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `the item's progress is the sum of its files, not the current one's`() =
        runTest {
            // Otherwise a 2 GB film would jump to 100 % while its 40 KB poster finished.
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any()) } returns 500L

            queue().drain(listener)

            val (bytes, _) = listener.progress.last()
            bytes shouldBe 1_000L
        }

    // ---- the session gate (cold start) ------------------------------------------------------------

    @Test
    fun `a cold start restores the session before the queue needs a URL`() =
        runTest {
            // WorkManager restarts this worker the moment the process comes up, before anything in
            // the UI has restored anything.
            queueWith(download())

            queue().drain(listener)

            coVerify(exactly = 1) { sessionGate.ensureSession() }
        }

    @Test
    fun `no session parks the queue instead of failing the item`() =
        runTest {
            // The regression this pins: the item used to go ERROR with the SDK's own
            // "Required value baseUrl is null" text, and the user had to press Retry on a download
            // that was never broken.
            coEvery { sessionGate.ensureSession() } returns false
            queueWith(download())

            queue().drain(listener) shouldBe DrainOutcome.NO_SESSION

            coVerify(exactly = 0) { downloadDao.setStatus(any(), DownloadStatus.ERROR, any(), any()) }
            coVerify(exactly = 0) { downloadDao.nextRunnable() }
            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a parked queue still puts interrupted rows back to Waiting`() =
        runTest {
            // Otherwise a row left DOWNLOADING by the killed process would render as a transfer
            // that no process is performing until a session appears.
            coEvery { sessionGate.ensureSession() } returns false
            queueWith(download())

            queue().drain(listener)

            coVerify(exactly = 1) { downloadDao.requeueInterrupted(NOW) }
            listener.idleCount shouldBe 1
        }

    // ---- essential vs optional ------------------------------------------------------------------

    @Test
    fun `a failing media file marks the item ERROR`() =
        runTest {
            queueWith(download())
            coEvery { downloader.download(match { it.contains("download") }, any(), any(), any(), any()) } throws
                IOException("connection reset")

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            // The stored message is user copy, not the exception's text — see DownloadErrorCopy.
            coVerify {
                downloadDao.setStatus(
                    uuid(1),
                    DownloadStatus.ERROR,
                    NOW,
                    "Couldn't reach your server. The download will retry.",
                )
            }
        }

    @Test
    fun `a failure never shows the user the exception's own text`() =
        runTest {
            // The device walk found a queue row reading "Download failed: Required value baseUrl is
            // null. Provide it by setting ApiClient.baseUrl." — SDK internals on screen.
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any()) } throws
                IllegalStateException("Required value baseUrl is null. Provide it by setting ApiClient.baseUrl.")
            val message = slot<String>()
            coEvery {
                downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, NOW, capture(message))
            } just Runs

            queue().drain(listener)

            message.captured shouldNotContain "baseUrl"
            message.captured shouldNotContain "ApiClient"
        }

    @Test
    fun `a failing poster leaves the item DOWNLOADED`() =
        runTest {
            // The plan's optional-file rule: the file row goes ERROR, the item stays playable.
            queueWith(download())
            coEvery { downloader.download(match { it.contains("image") }, any(), any(), any(), any()) } throws
                IOException("404")

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `a failing file is recorded on its own row`() =
        runTest {
            queueWith(download())
            coEvery { downloader.download(match { it.contains("image") }, any(), any(), any(), any()) } throws
                IOException("404")

            queue().drain(listener)

            coVerify { downloadDao.setFileStatus(any(), DownloadStatus.ERROR) }
        }

    @Test
    fun `a denied download policy retries the media file on the video stream`() =
        runTest {
            queueWith(download())
            every { urls.videoStreamUrl(any(), any()) } returns "https://server/videos/stream"
            coEvery { downloader.download("https://server/download", any(), any(), any(), any()) } throws
                DownloadHttpException(code = 403, url = "https://server/download")

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // Same bytes, a route the server does not gate on `enableContentDownloading`.
            coVerify { downloader.download("https://server/videos/stream", any(), any(), any(), any()) }
            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `a non-403 error on the media file is not retried`() =
        runTest {
            queueWith(download())
            coEvery { downloader.download("https://server/download", any(), any(), any(), any()) } throws
                DownloadHttpException(code = 500, url = "https://server/download")

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify(exactly = 0) { urls.videoStreamUrl(any(), any()) }
        }

    // ---- download quality (M9) ------------------------------------------------------------------

    @Test
    fun `the plan is built from the quality on the row, not from the live preference`() =
        runTest {
            queueWith(download(quality = DownloadQuality.MEDIUM))
            every { urls.transcodedVideoUrl(any(), any(), any()) } returns "https://server/videos/stream.mkv"

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // The bytes already on disk were fetched at this quality; re-reading the preference
            // here is what would let a setting change corrupt a half-finished file.
            verify { urls.transcodedVideoUrl(uuid(1), "source-1", DownloadQuality.MEDIUM) }
            coVerify(exactly = 0) { urls.mediaUrl(any()) }
        }

    @Test
    fun `a 403 on a transcoded download is not retried on the static stream`() =
        runTest {
            // That fallback exists to route around `enableContentDownloading`; taking it here would
            // hand the user the original file they asked the server to shrink.
            queueWith(download(quality = DownloadQuality.LOW))
            every { urls.transcodedVideoUrl(any(), any(), any()) } returns "https://server/videos/stream.mkv"
            coEvery { downloader.download("https://server/videos/stream.mkv", any(), any(), any(), any()) } throws
                DownloadHttpException(code = 403, url = "https://server/videos/stream.mkv")

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify(exactly = 0) { urls.videoStreamUrl(any(), any()) }
        }

    @Test
    fun `an unknown file size falls back to the size the enqueue step estimated`() =
        runTest {
            // A transcode is chunked, so `FileDownloader` reports total 0 for as long as it is
            // running. Without the estimate the item's total would collapse onto its downloaded
            // bytes and the queue tab would read 100 % from the first chunk.
            queueWith(download(quality = DownloadQuality.MEDIUM, bytesTotal = 4_000L))
            every { urls.transcodedVideoUrl(any(), any(), any()) } returns "https://server/videos/stream.mkv"
            coEvery { downloader.download(any(), any(), any(), any(), any()) } coAnswers {
                // The fifth argument, not the last: MockK hands a suspending call its continuation.
                arg<ProgressCallback>(4).onProgress(300L, 0L)
                300L
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // 300 bytes of the poster plus 300 of the film, against the estimate rather than
            // against a total that only knows about the bytes already written.
            listener.progress shouldContain (600L to 4_000L)
        }

    @Test
    fun `a generous estimate cannot leave a finished item short of complete`() =
        runTest {
            // Every file has reported a real size by the end, so the estimate is dropped and the
            // exact sum wins — a download that ends at 90 % of a guess is worse than no guess.
            queueWith(download(bytesTotal = 10_000L))
            coEvery { downloader.download(any(), any(), any(), any(), any()) } returns 100L

            queue().drain(listener)

            listener.progress.last() shouldBe (200L to 200L)
        }

    // ---- the live size projection (schema v6) ---------------------------------------------------

    @Test
    fun `a projection over the ceiling is clamped to the ceiling the enqueue step promised`() =
        runTest {
            givenTranscodeOfAnHour(ceiling = 10_000_000L)
            // 2 000 bytes bought only 100 ms — all container header, the state of every transcode
            // in its opening moment. Extrapolated that is 72 MB, which is not a promise to make.
            givenMediaStream(clusterMillis = 100L, reportedBytes = 2_000L)

            queue().drain(listener)

            projections shouldContain 10_000_000L
        }

    @Test
    fun `a projection well under the ceiling is the one the row ends up carrying`() =
        runTest {
            givenTranscodeOfAnHour(ceiling = 10_000_000L)
            // 2 000 bytes bought an hour of media — an encoder producing a tiny file.
            givenMediaStream(clusterMillis = 3_600_000L, reportedBytes = 2_000L)

            queue().drain(listener)

            // The projection is the media file's 2 000 bytes plus the poster's known 400.
            projections shouldContain 2_400L
        }

    @Test
    fun `the projection is cleared once the media file is whole`() =
        runTest {
            givenTranscodeOfAnHour(ceiling = 10_000_000L)
            givenMediaStream(clusterMillis = 3_600_000L, reportedBytes = 2_000L)

            queue().drain(listener)

            // At that point the size is not projected, it is measured — and `bytesTotal` snaps to
            // the sum of real sizes, which is the number the row should be divided by.
            projections.last().shouldBeNull()
        }

    @Test
    fun `an original download never projects anything`() =
        runTest {
            queueWith(download(bytesTotal = 10_000L))

            queue().drain(listener)

            // Its total is the server's own file size; there is nothing to improve on.
            projections.filterNotNull().shouldBeEmpty()
        }

    @Test
    fun `a row the enqueue step marked exact is not second-guessed by the scanner`() =
        runTest {
            // A stream copy: `bytesTotal` predicts the actual file, so flipping the row to an
            // approximate figure mid-transfer would be a downgrade, not a refinement.
            givenTranscodeOfAnHour(ceiling = 10_000_000L, sizeIsExact = true)
            givenMediaStream(clusterMillis = 3_600_000L, reportedBytes = 2_000L)

            queue().drain(listener)

            projections.filterNotNull().shouldBeEmpty()
        }

    @Test
    fun `a seeded projection holds until the stream has a cluster of its own to offer`() =
        runTest {
            givenTranscodeOfAnHour(ceiling = 10_000_000L, seed = 3_000_000L)
            // Bytes arrive, but not one cluster header among them, so there is nothing to measure.
            givenMediaStream(clusterMillis = null, reportedBytes = 2_000L)

            queue().drain(listener)

            // The seed from the show's finished episodes is still the best answer available, and
            // blanking it would make the row's wording flap from "~3,0 MB" back to "up to 10,0 MB".
            projections shouldContain 3_000_000L + 400L
        }

    @Test
    fun `a transcode of an item with no runtime has nothing to extrapolate to`() =
        runTest {
            every { itemMapper.toDtoOrNull(any()) } returns movie(runTimeTicks = null)
            queueWith(download(quality = DownloadQuality.LOW, bytesTotal = 10_000_000L))
            every { urls.transcodedVideoUrl(any(), any(), any()) } returns TRANSCODE_URL
            givenMediaStream(clusterMillis = 3_600_000L, reportedBytes = 2_000L)

            queue().drain(listener)

            projections.filterNotNull().shouldBeEmpty()
        }

    // ---- cancellation ---------------------------------------------------------------------------

    @Test
    fun `cancellation puts the row back in the queue rather than failing it`() =
        runTest {
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any()) } throws CancellationException("paused")

            runCatching { queue().drain(listener) }

            // This is what makes the next run resume from the byte offset instead of restarting.
            coVerify { downloadDao.requeueIfDownloading(uuid(1), NOW) }
            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, any(), any()) }
        }

    @Test
    fun `cancellation never writes QUEUED over whatever status the row now has`() =
        runTest {
            // The M9 bug (docs/POLISH.md, "pausing a download doesn't work"): *Pause* writes
            // `PAUSED` and then cancels the work to interrupt the transfer, so this handler runs
            // *after* the user's own write. An unconditional `setStatus(QUEUED)` here undid it and
            // `nextRunnable` picked the item straight back up — pause looked like it did nothing.
            // The status test lives in the statement (`requeueIfDownloading`) so it cannot race.
            queueWith(download())
            coEvery { downloader.download(any(), any(), any(), any(), any()) } throws CancellationException("paused")

            runCatching { queue().drain(listener) }

            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), DownloadStatus.QUEUED, any(), any()) }
        }

    // ---- the file plan --------------------------------------------------------------------------

    @Test
    fun `planned files are inserted in plan order`() =
        runTest {
            val inserted = mutableListOf<DownloadFileEntity>()
            coEvery { downloadDao.insertFile(capture(inserted)) } answers { nextFileId++ }
            queueWith(download())

            queue().drain(listener)

            inserted.map { it.type } shouldContainExactly
                listOf(DownloadFileType.IMAGE_PRIMARY, DownloadFileType.MEDIA)
        }

    @Test
    fun `an existing file row is reused so its bytes on disk keep their resume offset`() =
        runTest {
            val existing =
                file(id = 42L, type = DownloadFileType.MEDIA, bytesDownloaded = 900_000L)
            queueWith(download(), files = listOf(existing))
            val updated = slot<DownloadFileEntity>()
            coEvery { downloadDao.updateFile(capture(updated)) } just Runs

            queue().drain(listener)

            updated.captured.id shouldBe 42L
            coVerify(exactly = 1) { downloadDao.insertFile(match { it.type == DownloadFileType.IMAGE_PRIMARY }) }
        }

    @Test
    fun `a retry targets the same file names as the first attempt`() =
        runTest {
            // The M7 regression, end to end: attempt one plans the media file from the DTO's `path`
            // (`Backrooms…-BATGirl.mkv`), the retry runs from a DTO with no `path` and would plan
            // `Backrooms (2026).mkv` — orphaning 1.38 GB and restarting the transfer from zero.
            val rows = mutableListOf<DownloadFileEntity>()
            coEvery { downloadDao.insertFile(capture(rows)) } answers { nextFileId++ }
            every { itemMapper.toDtoOrNull(any()) } returns
                movie(name = "Backrooms", year = 2026, path = "/media/Backrooms.2026.MULTi-BATGirl.mkv")
            queueWith(download())

            queue().drain(listener)
            val firstPlan = rows.toList()
            firstPlan.first { it.type == DownloadFileType.MEDIA }.fileName shouldBe
                "Backrooms.2026.MULTi-BATGirl.mkv"

            // Second run: the persisted rows exist, and the DTO no longer carries a path.
            every { itemMapper.toDtoOrNull(any()) } returns movie(name = "Backrooms", year = 2026, path = null)
            val stored = firstPlan.mapIndexed { index, row -> row.copy(id = index + 1L) }
            queueWith(download(), files = stored)
            val updated = mutableListOf<DownloadFileEntity>()
            coEvery { downloadDao.updateFile(capture(updated)) } just Runs

            queue().drain(listener)

            // Same rows, same names, same files on disk — so the byte offset still means something.
            updated.map { it.fileName } shouldContainExactly stored.map { it.fileName }
            updated.map { it.id } shouldContainExactly stored.map { it.id }
            // Nothing new was inserted: the second run re-used every row the first one wrote.
            rows.size shouldBe firstPlan.size
            val target = File("/tmp/downloads/Backrooms.2026.MULTi-BATGirl.mkv")
            coVerify { downloader.download(any(), target, any(), any(), any()) }
        }

    @Test
    fun `a first attempt with no rows plans freely`() =
        runTest {
            // The re-plan path is only reachable when nothing is on disk: a re-enqueue after a
            // delete has no `download_files` rows *and* no directory, so a fresh name is safe.
            every { itemMapper.toDtoOrNull(any()) } returns movie(name = "Backrooms", year = 2026, path = null)
            val rows = mutableListOf<DownloadFileEntity>()
            coEvery { downloadDao.insertFile(capture(rows)) } answers { nextFileId++ }
            queueWith(download())

            queue().drain(listener)

            rows.first { it.type == DownloadFileType.MEDIA }.fileName shouldBe "Arrival (2016).mkv"
        }

    @Test
    fun `URLs are rebuilt on every run, not read back from the row`() =
        runTest {
            // `ServerReachabilityProbe` rotates the base URL between LAN and remote; a row queued
            // at home and run on mobile data must be fetched from the address that answers now.
            val stale = file(id = 42L, type = DownloadFileType.MEDIA, url = "https://old-address/download")
            queueWith(download(), files = listOf(stale))

            queue().drain(listener)

            coVerify { downloader.download("https://server/download", any(), any(), any(), any()) }
        }

    // ---- deletion while downloading ---------------------------------------------------------------

    @Test
    fun `an item deleted mid-transfer stops instead of re-creating its directory`() =
        runTest {
            // WorkManager's cancellation is asynchronous, so the delete cascade can unlink the
            // files while this loop is still between two of them; `FileDownloader` re-creates the
            // item directory for every file it opens, which would leave files no row points at.
            queueWith(download())
            coEvery { downloadDao.get(uuid(1)) } returns null

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any()) }
            // Nothing is "downloaded": the item is gone, not finished.
            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, any(), any()) }
        }

    @Test
    fun `an item with no cached metadata fails rather than downloading nothing`() =
        runTest {
            queueWith(download())
            coEvery { itemDao.getItem(any()) } returns null

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, NOW, any()) }
        }

    @Test
    fun `the queue reports idle once it drains`() =
        runTest {
            queueWith(download())

            queue().drain(listener)

            listener.idleCount shouldBe 1
        }

    @Test
    fun `progress is written to Room, which is the single source of truth`() =
        runTest {
            queueWith(download())

            queue().drain(listener)

            coVerify(atLeast = 1) { downloadDao.updateProgress(uuid(1), any(), any(), any(), NOW) }
            listener.progress.shouldNotBeEmpty()
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** A one-hour item queued at `LOW`, so the media file is a transcode with a ceiling to beat. */
    private fun givenTranscodeOfAnHour(
        ceiling: Long,
        seed: Long? = null,
        sizeIsExact: Boolean = false,
    ) {
        every { itemMapper.toDtoOrNull(any()) } returns movie(runTimeTicks = HOUR_TICKS)
        every { urls.transcodedVideoUrl(any(), any(), any()) } returns TRANSCODE_URL
        queueWith(
            download(
                quality = DownloadQuality.LOW,
                bytesTotal = ceiling,
                projectedBytes = seed,
                sizeIsExact = sizeIsExact,
            ),
        )
    }

    /**
     * The poster lands at a known 400 bytes; the media file feeds [clusterMillis] of Matroska into
     * the chunk sink and then reports [reportedBytes] with an unknown total, as a chunked transcode
     * does for its whole life.
     *
     * @param clusterMillis `null` writes bytes carrying no cluster header at all — the state of the
     *   stream before its first cluster arrives.
     */
    private fun givenMediaStream(
        clusterMillis: Long?,
        reportedBytes: Long,
    ) {
        coEvery { downloader.download(IMAGE_URL, any(), any(), any(), any()) } returns 400L
        coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any()) } coAnswers {
            val body = clusterMillis?.let(::clusterBytes) ?: ByteArray(16) { 0x11 }
            arg<MediaChunkSink?>(3)?.onChunk(body, 0, body.size)
            arg<ProgressCallback>(4).onProgress(reportedBytes, 0L)
            reportedBytes
        }
    }

    /** One Matroska cluster whose `Timestamp` is [millis], at the default 1 ms scale. */
    private fun clusterBytes(millis: Long): ByteArray {
        val value =
            byteArrayOf(
                (millis shr 24).toByte(),
                (millis shr 16).toByte(),
                (millis shr 8).toByte(),
                millis.toByte(),
            )
        // Cluster id, size 6, then `Timestamp` (0xE7) as a four-byte unsigned first child.
        return byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0x86.toByte(), 0xE7.toByte(), 0x84.toByte()) + value
    }

    private fun queue() =
        DownloadQueue(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            planner = DownloadFilePlanner(urls),
            storage = storage,
            downloader = downloader,
            sessionGate = sessionGate,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun queueWith(
        vararg downloads: DownloadEntity,
        files: List<DownloadFileEntity> = emptyList(),
    ) {
        val queued = downloads.map { withFiles(it, files) }
        coEvery { downloadDao.nextRunnable() } returnsMany (queued + null)
    }

    private fun withFiles(
        download: DownloadEntity,
        files: List<DownloadFileEntity> = emptyList(),
    ) = DownloadWithFiles(download = download, files = files)

    /** Records what the worker would have shown in its notification. */
    private class RecordingListener : DownloadQueueListener {
        val progress = mutableListOf<Pair<Long, Long>>()
        var idleCount = 0

        override suspend fun onProgress(
            download: DownloadEntity,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ) {
            progress += bytesDownloaded to bytesTotal
        }

        override suspend fun onIdle() {
            idleCount++
        }
    }

    private companion object {
        const val IMAGE_URL = "https://server/image"
        const val TRANSCODE_URL = "https://server/videos/stream.mkv"

        /** One hour in `runTimeTicks` (100 ns each). */
        const val HOUR_TICKS = 36_000_000_000L

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
