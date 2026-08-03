package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
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
import dev.jellyboost.data.downloads.DownloadFixtures.audioStream
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.file
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/**
 * Unit tests for the guards [DownloadQueue] applies *before* touching a file — the claim on the
 * item row (audit DL-03), the already-whole-on-disk skip (audit DL-02) and the cancellation
 * clean-up of a half-written strip (audit DL-12).
 *
 * Separate from [DownloadQueueTest], which owns the transfer itself: what lives here is only what
 * keeps a re-entered or interrupted item from spending bytes it has already paid for, or from
 * overwriting a status the user just wrote.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueFileGuardTest {
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
    private val extractor = FakeExtractor()

    /** The whole-file and sidecar guards read real file sizes, so the tests write real files. */
    @TempDir
    lateinit var directory: File

    private var nextFileId = 1L

    @BeforeEach
    fun setUp() {
        every { storage.prepareItemDirectory(any()) } returns File("/tmp/downloads")
        every { storage.resolve(any(), any()) } answers { File(directory, secondArg<String>()) }
        coEvery { downloadDao.insertFile(any()) } answers { nextFileId++ }
        coEvery { downloadDao.get(any()) } returns download()
        coEvery { itemDao.getItem(any()) } returns ITEM_ENTITY
        every { itemMapper.toDtoOrNull(any()) } returns movie()
        every { urls.mediaUrl(any()) } returns "https://server/download"
        every { urls.imageUrl(any(), any(), any(), any()) } returns "https://server/image"
        coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { downloadDao.markDownloadingIfRunnable(any(), any()) } returns 1
        coEvery { seeder.seedFor(any(), any(), any(), any(), any()) } returns null
        coEvery { sweeper.sweep() } returns 0L
    }

    // ---- the guarded claim (DL-03) --------------------------------------------------------------

    @Test
    fun `a row whose status changed between being picked and being claimed is left alone`() =
        runTest {
            // The DL-03 race: Pause writes PAUSED and then stops the worker, and a drain sitting
            // between nextRunnable() and the start of the transfer used to write DOWNLOADING over
            // it — the cancellation then re-queued the row and the paused item downloaded anyway.
            // A claim that touches zero rows means the row changed hands; nothing is transferred
            // and nothing is written.
            queueWith(download())
            coEvery { downloadDao.markDownloadingIfRunnable(uuid(1), NOW) } returns 0

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { downloadDao.setStatus(uuid(1), any(), any(), any()) }
        }

    // ---- file-granular resume (DL-02) -----------------------------------------------------------

    @Test
    fun `a media file already downloaded whole is not re-fetched when the item re-enters the queue`() =
        runTest {
            // The DL-02 window: the audio lane keeps running for minutes after the film itself has
            // finished, and any interruption there re-queues the whole item. The media row is
            // already DOWNLOADED and its transcode is complete on disk — re-entering `downloadOne`
            // used to truncate it and restart the server-side encode from byte zero, because a
            // live encode is flagged un-resumable.
            every { urls.transcodedVideoUrl(any(), any(), any(), any()) } returns TRANSCODE_URL
            val media = File(directory, "Arrival (2016) (medium).mkv").apply { writeBytes(ByteArray(500)) }
            queueWith(
                download(quality = DownloadQuality.MEDIUM),
                files = listOf(mediaRow(media, bytesTotal = 500L)),
            )

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify(exactly = 0) { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) }
            media.length() shouldBe 500L
        }

    @Test
    fun `a DOWNLOADED row whose bytes no longer match what is on disk is fetched again`() =
        runTest {
            // The other half of the guard: the row's recorded size is the file's final size, so a
            // truncated file (a swept volume, a torn write) is not the file the row describes.
            every { urls.transcodedVideoUrl(any(), any(), any(), any()) } returns TRANSCODE_URL
            val media = File(directory, "Arrival (2016) (medium).mkv").apply { writeBytes(ByteArray(100)) }
            queueWith(
                download(quality = DownloadQuality.MEDIUM),
                files = listOf(mediaRow(media, bytesTotal = 500L)),
            )

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify(exactly = 1) { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) }
        }

    // ---- cancellation of the strip (DL-12) ------------------------------------------------------

    @Test
    fun `a strip cancelled midway leaves no half-written sidecar behind`() =
        runTest {
            // The cancellation branch used to keep the truncated m4a — junk occupying disk for as
            // long as the pause lasted, for a file the next attempt rebuilds from its first byte
            // anyway (the row stays DOWNLOADING, so nothing reads it as whole).
            givenTwoLanguageTranscode()
            extractor.failure = CancellationException("paused")

            runCatching { queue().drain(listener) }

            partFile().exists() shouldBe false
            sidecarFile().exists() shouldBe false
            // Cancelled, not failed: the row must not carry an ERROR the retry never clears.
            coVerify(exactly = 0) { downloadDao.setFileStatus(any(), DownloadStatus.ERROR) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun mediaRow(
        media: File,
        bytesTotal: Long,
    ) = file(
        id = 7L,
        type = DownloadFileType.MEDIA,
        fileName = media.name,
        path = media.absolutePath,
        bytesDownloaded = bytesTotal,
        bytesTotal = bytesTotal,
        status = DownloadStatus.DOWNLOADED,
    )

    /** A transcoded film with two audio languages, one baked in — one `AUDIO` row, for stream 2. */
    private fun givenTwoLanguageTranscode() {
        every { itemMapper.toDtoOrNull(any()) } returns
            movie(
                streams = listOf(audioStream(index = 1), audioStream(index = 2)),
                defaultAudioStreamIndex = 1,
            )
        every { urls.transcodedVideoUrl(any(), any(), any(), any()) } returns TRANSCODE_URL
        every { urls.audioStreamUrl(uuid(1), "source-1", 2) } returns AUDIO_URL
        coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
            secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
            FETCH_BYTES
        }
        queueWith(download(quality = DownloadQuality.MEDIUM, bakedAudioStreamIndex = 1))
    }

    private fun sidecarFile() = File(directory, "audio.2.eng.m4a")

    private fun partFile() = File(directory, "audio.2.eng.m4a${DownloadQueue.PART_SUFFIX}")

    private fun queue() =
        DownloadQueue(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            planner = DownloadFilePlanner(urls),
            storage = storage,
            downloader = downloader,
            extractor = extractor,
            seeder = seeder,
            sweeper = sweeper,
            sessionGate = sessionGate,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun queueWith(
        vararg downloads: DownloadEntity,
        files: List<DownloadFileEntity> = emptyList(),
    ) {
        val queued = downloads.map { DownloadWithFiles(download = it, files = files) }
        coEvery { downloadDao.nextRunnable() } returnsMany (queued + null)
    }

    /** The strip stage, without a `Looper`, a muxer or a device — see [DownloadQueueTest]'s twin. */
    private class FakeExtractor : AudioSidecarExtractor {
        var failure: Exception? = null

        override suspend fun extract(
            source: File,
            target: File,
        ) {
            failure?.let { error ->
                target.writeBytes(ByteArray(1))
                throw error
            }
            target.writeBytes(ByteArray(SIDECAR_BYTES.toInt()))
        }
    }

    private class RecordingListener : DownloadQueueListener {
        override suspend fun onProgress(
            download: DownloadEntity,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ) = Unit

        override suspend fun onIdle() = Unit
    }

    private companion object {
        const val TRANSCODE_URL = "https://server/videos/stream.mkv"
        const val AUDIO_URL = "https://server/audio/stream.mkv?audioStreamIndex=2"
        const val FETCH_BYTES = 200L
        const val SIDECAR_BYTES = 42L

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
