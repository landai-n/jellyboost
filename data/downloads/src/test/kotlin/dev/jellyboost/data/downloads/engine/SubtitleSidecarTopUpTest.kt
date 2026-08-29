package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.subtitleStream
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.engine.FileDownloader
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.DownloadUrlFactory
import dev.jellyboost.data.downloads.storage.DownloadStorage
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.ImageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Two properties carry the whole class, and both are about what it *refuses* to do: it never touches
 * the media file (re-queueing the row would re-download a transcode from zero, since the server
 * ignores `Range` on one) and it never re-fetches a sidecar that is already whole, which is what makes
 * running it on every connectivity edge free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSidecarTopUpTest {
    @TempDir
    lateinit var directory: File

    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val storage = mockk<DownloadStorage>()
    private val downloader = mockk<FileDownloader>()
    private val planner = DownloadFilePlanner(FakeUrls)
    private val preferences =
        mockk<AppPreferences> { every { downloadOverWifiOnly } returns flowOf(false) }
    private var meteredNow = false

    private val inserted = mutableListOf<DownloadFileEntity>()
    private val updated = mutableListOf<DownloadFileEntity>()
    private val requested = mutableListOf<String>()

    private fun topUp() =
        SubtitleSidecarTopUp(
            downloadDao = downloadDao,
            planner = planner,
            storage = storage,
            downloader = downloader,
            preferences = preferences,
            metered = { meteredNow },
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
        coEvery { downloader.download(capture(requested), any(), any(), any(), any(), any()) } coAnswers {
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

    // ---- the Wi-Fi-only rule ----------------------------------------------------------------------

    @Test
    fun `a Wi-Fi-only user gets no top-up over a metered connection`() =
        runTest {
            // This runs on the application scope, not inside the constrained worker — the UNMETERED
            // constraint that normally *is* the Wi-Fi-only preference does not apply here.
            every { preferences.downloadOverWifiOnly } returns flowOf(true)
            meteredNow = true
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(elementaire())) shouldBe 0

            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a Wi-Fi-only user is still topped up on an unmetered connection`() =
        runTest {
            every { preferences.downloadOverWifiOnly } returns flowOf(true)
            meteredNow = false
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(elementaire())) shouldBe 2
        }

    @Test
    fun `a user without the preference is topped up whatever the connection is`() =
        runTest {
            every { preferences.downloadOverWifiOnly } returns flowOf(false)
            meteredNow = true
            given(files = listOf(mediaFile()))

            topUp().topUp(listOf(elementaire())) shouldBe 2
        }

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

            // A transcode cannot be resumed, so re-planning a finished row would re-download the film
            // for a 40 KB file.
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

            // `DownloadQueue.reconcile`'s rule: the stored name is what is on disk, and the plan
            // cannot be trusted to reproduce it.
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

            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `an original download is not given sidecars for tracks its file already holds`() =
        runTest {
            given(files = listOf(mediaFile()), quality = DownloadQuality.ORIGINAL)

            // The top-up asks the planner with the row's own quality, so a repair gives a download the
            // files today's planner would have given *it*, no more.
            topUp().topUp(listOf(elementaire())) shouldBe 0
        }

    @Test
    fun `a finished download is never given the audio sidecars today's plan would add`() =
        runTest {
            // Audio sidecars are for new downloads only: an extra language is ~165 MB through a
            // junk-video transcode, not a repair to perform silently behind a user who has the film.
            // The `type in TOPPED_UP_TYPES` filter is the whole guard: it was widened to fonts on
            // 2026-08-29 (tens of KB, and useless without the subtitle beside them) and audio stayed
            // out for the size reason above. Widening it again has to be a decision.
            given(files = listOf(mediaFile()))
            val dubbed =
                movie(
                    streams =
                        listOf(
                            DownloadFixtures.audioStream(index = 1, language = "eng"),
                            DownloadFixtures.audioStream(index = 2, language = "fra"),
                            subtitleStream(index = 6, language = "fra", external = false),
                        ),
                    defaultAudioStreamIndex = 1,
                )

            // The one missing subtitle is still fetched: this pins the audio, not the top-up.
            topUp().topUp(listOf(dubbed)) shouldBe 1

            inserted.map { it.type }.distinct() shouldContainExactly listOf(DownloadFileType.SUBTITLE)
            requested.none { it.startsWith("audio://") } shouldBe true
        }

    @Test
    fun `a download taken before styled ASS shipped gains the fonts its sidecars name`() =
        runTest {
            // The reason fonts joined the top-up: an item downloaded before the font branch existed
            // has its ASS sidecar and none of the faces it names, and would draw in the fallback
            // family forever otherwise.
            given(files = listOf(mediaFile()))
            val styled =
                movie(
                    streams = listOf(subtitleStream(index = 6, codec = "ass", language = "fra", external = false)),
                    attachments = listOf(DownloadFixtures.fontAttachment(index = 4)),
                )

            topUp().topUp(listOf(styled)) shouldBe 2

            inserted.map { it.type } shouldContainExactly
                listOf(DownloadFileType.SUBTITLE, DownloadFileType.FONT)
            requested.any { it.startsWith("attachment://") } shouldBe true
        }

    @Test
    fun `a font already on disk is not fetched again`() =
        runTest {
            val fontOnDisk = File(directory, "font.4.Face-4.ttf").apply { writeText("face bytes") }
            given(
                files =
                    listOf(
                        mediaFile(),
                        fontRow(streamIndex = 4, path = fontOnDisk.absolutePath),
                    ),
            )
            val styled =
                movie(
                    streams = listOf(subtitleStream(index = 6, codec = "ass", language = "fra", external = false)),
                    attachments = listOf(DownloadFixtures.fontAttachment(index = 4)),
                )

            // Only the subtitle is missing.
            topUp().topUp(listOf(styled)) shouldBe 1

            inserted.map { it.type }.distinct() shouldContainExactly listOf(DownloadFileType.SUBTITLE)
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
                downloader.download(match { it.endsWith("6.srt") }, any(), any(), any(), any(), any())
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

    private fun fontRow(
        streamIndex: Int,
        path: String,
    ) = DownloadFixtures.file(
        id = FILE_ID + 1,
        itemId = uuid(1),
        type = DownloadFileType.FONT,
        fileName = "font.$streamIndex.Face-$streamIndex.ttf",
        streamIndex = streamIndex,
        status = DownloadStatus.DOWNLOADED,
        path = path,
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

/** Enough of a [DownloadUrlFactory] for the planner; only the sidecar URLs are ever fetched. */
private object FakeUrls : DownloadUrlFactory {
    override fun mediaUrl(itemId: UUID) = "download://$itemId"

    override fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "stream://$itemId"

    override fun staticAudioUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "audio-static://$itemId"

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

    override fun audioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        streamIndex: Int,
    ) = "audio://$itemId/$streamIndex"

    override fun attachmentUrl(
        itemId: UUID,
        mediaSourceId: String,
        index: Int,
    ) = "attachment://$itemId/$index"
}
