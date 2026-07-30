package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.data.downloads.DownloadFixtures.download
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.subtitleStream
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import dev.jellyfinnative.data.downloads.engine.FileDownloader
import dev.jellyfinnative.data.downloads.plan.DownloadFilePlanner
import dev.jellyfinnative.data.downloads.plan.DownloadUrlFactory
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.ImageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Unit tests for [SubtitleSidecarTopUp] — the repair path for downloads whose file plan has moved on
 * without them (docs/notes/offline-multitrack-design.md, phase 0).
 *
 * Two properties carry the whole class, and both are about what it *refuses* to do: it never touches
 * the media file (re-queueing the row would re-download a transcode from zero, since the server
 * ignores `Range` on one) and it never re-fetches a sidecar that is already whole, which is what
 * makes running it on every connectivity edge free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSidecarTopUpTest {
    @TempDir
    lateinit var directory: File

    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val storage = mockk<DownloadStorage>()
    private val downloader = mockk<FileDownloader>()
    private val planner = DownloadFilePlanner(FakeUrls)

    private val inserted = mutableListOf<DownloadFileEntity>()
    private val updated = mutableListOf<DownloadFileEntity>()
    private val requested = mutableListOf<String>()

    private fun topUp() =
        SubtitleSidecarTopUp(
            downloadDao = downloadDao,
            planner = planner,
            storage = storage,
            downloader = downloader,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun given(
        files: List<DownloadFileEntity>,
        quality: DownloadQuality = DownloadQuality.MEDIUM,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ) {
        coEvery { downloadDao.getWithFiles(uuid(1)) } returns
            DownloadWithFiles(
                download = download(itemId = uuid(1), status = status, quality = quality),
                files = files,
            )
        every { storage.prepareItemDirectory(any()) } returns directory
        every { storage.resolve(any(), any()) } answers { File(directory, secondArg<String>()) }
        coEvery { downloadDao.insertFile(capture(inserted)) } returns FILE_ID
        coEvery { downloadDao.updateFile(capture(updated)) } just Runs
        coEvery { downloader.download(capture(requested), any(), any(), any(), any()) } coAnswers {
            secondArg<File>().writeText("1\n00:00:01,000 --> 00:00:02,000\nbonjour\n")
            SIDECAR_BYTES
        }
    }

    /** Élémentaire's two embedded French SRTs — the streams phase 0 exists for. */
    private fun elementaire() =
        movie(
            streams =
                listOf(
                    subtitleStream(index = 6, language = "fra", external = false),
                    subtitleStream(index = 7, language = "fra", external = false),
                ),
        )

    // ---- what it fetches --------------------------------------------------------------------------

    @Test
    fun `a finished transcode gains the embedded sidecars it was downloaded without`() =
        runTest {
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(elementaire())) shouldBe 2

            inserted.map { it.streamIndex } shouldContainExactly listOf(6, 7)
            inserted.map { it.fileName } shouldContainExactly
                listOf("subtitle.6.fra.srt", "subtitle.7.fra.srt")
        }

    @Test
    fun `the media file is never among the files it fetches`() =
        runTest {
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(elementaire()))

            // The whole reason this is not "put the row back in the queue": a transcode cannot be
            // resumed, so re-planning a finished row would re-download the film for a 40 KB file.
            requested.none { it.startsWith("transcode://") || it.startsWith("download://") } shouldBe true
            inserted.map { it.type }.distinct() shouldContainExactly listOf(DownloadFileType.SUBTITLE)
        }

    @Test
    fun `the fetched sidecar is recorded as downloaded, with the bytes it weighs`() =
        runTest {
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(movie(streams = listOf(subtitleStream(index = 6, external = false)))))

            coVerify { downloadDao.updateFileProgress(FILE_ID, SIDECAR_BYTES, SIDECAR_BYTES) }
            coVerify { downloadDao.setFileStatus(FILE_ID, DownloadStatus.DOWNLOADED) }
        }

    @Test
    fun `a sidecar that failed the first time is retried on its existing row`() =
        runTest {
            given(
                files =
                    listOf(
                        mediaFile(),
                        subtitleRow(streamIndex = 6, status = DownloadStatus.ERROR, path = "/nowhere/s.6.srt"),
                    ),
            )

            topUp().topUp(listOf(movie(streams = listOf(subtitleStream(index = 6, external = false)))))

            // Its row, its name — `DownloadQueue.reconcile`'s rule: the stored name is what is on
            // disk, and the plan cannot be trusted to reproduce it.
            inserted.shouldBeEmpty()
            updated.single().fileName shouldBe "kept-name.srt"
            updated.single().status shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun `a row whose sidecar vanished from disk is fetched again`() =
        runTest {
            given(
                files =
                    listOf(
                        mediaFile(),
                        subtitleRow(streamIndex = 6, path = File(directory, "gone.srt").absolutePath),
                    ),
            )

            topUp().topUp(listOf(movie(streams = listOf(subtitleStream(index = 6, external = false))))) shouldBe 1
        }

    // ---- what it leaves alone ---------------------------------------------------------------------

    @Test
    fun `a complete sidecar is not fetched again`() =
        runTest {
            val onDisk = File(directory, "s.6.srt").apply { writeText("already here") }
            given(files = listOf(mediaFile(), subtitleRow(streamIndex = 6, path = onDisk.absolutePath)))

            topUp().topUp(listOf(movie(streams = listOf(subtitleStream(index = 6, external = false))))) shouldBe 0

            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `an original download is not given sidecars for tracks its file already holds`() =
        runTest {
            given(files = listOf(mediaFile()), quality = DownloadQuality.ORIGINAL)

            // The planner decides this, and the top-up asks it with the row's own quality — so the
            // repair gives a download the files today's planner would have given *it*, no more.
            topUp().topUp(listOf(elementaire())) shouldBe 0
        }

    @Test
    fun `a download still in the queue is left entirely to the queue`() =
        runTest {
            given(files = listOf(mediaFile()), status = DownloadStatus.QUEUED)

            topUp().topUp(listOf(elementaire())) shouldBe 0

            coVerify(exactly = 0) { downloadDao.insertFile(any()) }
        }

    @Test
    fun `an item with no download row at all is ignored`() =
        runTest {
            coEvery { downloadDao.getWithFiles(any()) } returns null

            topUp().topUp(listOf(elementaire())) shouldBe 0
        }

    @Test
    fun `a bitmap subtitle is not repaired, because it could never have been fetched`() =
        runTest {
            given(files = listOf(mediaFile()))

            topUp()
                .topUp(
                    listOf(movie(streams = listOf(subtitleStream(index = 8, codec = "pgssub", external = false)))),
                ) shouldBe 0
        }

    // ---- what it survives -------------------------------------------------------------------------

    @Test
    fun `one sidecar failing does not cost the next one its fetch`() =
        runTest {
            given(files = listOf(mediaFile()))
            coEvery {
                downloader.download(match { it.endsWith("6.srt") }, any(), any(), any(), any())
            } throws IOException("404")

            topUp().topUp(listOf(elementaire())) shouldBe 1
        }

    @Test
    fun `an unavailable storage volume is survived, not thrown`() =
        runTest {
            given(files = listOf(mediaFile()))
            every { storage.prepareItemDirectory(any()) } throws IllegalStateException("no volume")

            topUp().topUp(listOf(elementaire())) shouldBe 0
        }

    @Test
    fun `a folder item is skipped rather than planned`() =
        runTest {
            // The refresher hands over whatever has a download row; a folder row is the doomed kind
            // `DownloadEnqueuer` deletes, and planning one throws.
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(DownloadFixtures.season(id = uuid(1)))) shouldBe 0
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun mediaFile() =
        DownloadFixtures.file(
            id = 1L,
            itemId = uuid(1),
            type = DownloadFileType.MEDIA,
            status = DownloadStatus.DOWNLOADED,
            path = File(directory, "film.mkv").absolutePath,
        )

    private fun subtitleRow(
        streamIndex: Int,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
        path: String,
    ) = DownloadFixtures.file(
        id = FILE_ID,
        itemId = uuid(1),
        type = DownloadFileType.SUBTITLE,
        fileName = "kept-name.srt",
        streamIndex = streamIndex,
        status = status,
        path = path,
    )

    private companion object {
        const val FILE_ID = 7L
        const val SIDECAR_BYTES = 41_000L
    }
}

/** Enough of a [DownloadUrlFactory] for the planner; only the subtitle URLs are ever fetched. */
private object FakeUrls : DownloadUrlFactory {
    override fun mediaUrl(itemId: UUID) = "download://$itemId"

    override fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "stream://$itemId"

    override fun transcodedVideoUrl(
        itemId: UUID,
        mediaSourceId: String?,
        quality: DownloadQuality,
        audioStreamIndex: Int?,
    ) = "transcode://$itemId"

    override fun imageUrl(
        itemId: UUID,
        imageType: ImageType,
        tag: String,
        fillWidth: Int,
    ) = "image://$itemId"

    override fun subtitleUrl(
        itemId: UUID,
        mediaSourceId: String,
        streamIndex: Int,
        format: String,
    ) = "subtitle://$itemId/$streamIndex.$format"

    override fun trickplayTileUrl(
        itemId: UUID,
        width: Int,
        tileIndex: Int,
    ) = "trickplay://$itemId/$tileIndex"
}
