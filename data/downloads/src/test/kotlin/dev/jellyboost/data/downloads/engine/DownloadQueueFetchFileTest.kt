package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.audioStream
import dev.jellyboost.data.downloads.DownloadFixtures.download
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.DownloadUrlFactory
import dev.jellyboost.data.downloads.storage.DownloadStorage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/**
 * Unit tests for the one rule `withFetchFile` states: a sidecar's fetch cannot be resumed, so its
 * part file is worthless however the transfer ends (docs/notes/audit-2026-08-06-quality.md,
 * CPX-12).
 *
 * Separate from [DownloadQueueTest], which owns the transfer itself, and from
 * [DownloadQueueFileGuardTest], which owns what happens *before* a byte is fetched. What lives
 * here is only the fetch file's lifetime — and the fact that an ordinary file has none, which is
 * the half of the rule a `finally` in the wrong place would quietly delete a download over.
 *
 * The rule was three catch arms before, so its three exits are three tests: the sidecar's fetch
 * failing, the same fetch being cancelled, and a plain success. What a *strip* failure or
 * cancellation costs is [DownloadQueueTest] and [DownloadQueueFileGuardTest]'s, unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueFetchFileTest {
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
    private val extractor = FakeExtractor(sidecarBytes = SIDECAR_BYTES)

    /** The fetch file is a real file, and whether it is still there is the whole assertion. */
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
        every { urls.imageUrl(any(), any(), any(), any()) } returns IMAGE_URL
        coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { downloadDao.markDownloadingIfRunnable(any(), any()) } returns 1
        coEvery { seeder.seedFor(any(), any(), any(), any(), any()) } returns null
        coEvery { sweeper.sweep() } returns 0L
        givenTwoLanguageTranscode()
    }

    @Test
    fun `a sidecar whose fetch fails keeps none of its junk video`() =
        runTest {
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                // What a died transcode leaves behind: part of the mkv already on disk.
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                throw IOException("the transcode died")
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // The optional-file rule marks the row, and the fetch goes with the attempt: it cannot
            // be resumed, so keeping it would cost the item's directory the whole junk video until
            // a retry that truncates it from byte zero anyway.
            coVerify { downloadDao.setFileStatus(AUDIO_FILE_ID, DownloadStatus.ERROR) }
            partFile().exists() shouldBe false
        }

    @Test
    fun `a sidecar whose fetch is cancelled keeps none of it either, and no failure on its row`() =
        runTest {
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                throw CancellationException("paused")
            }

            runCatching { queue().drain(listener) }

            // The same clean-up as a failure, for the opposite reason: a pause may last a week and
            // this is hundreds of megabytes of video nobody asked for. The *row* is treated
            // differently — a cancelled file is not a failed one, so it keeps DOWNLOADING and the
            // retry re-plans it rather than clearing an error off it.
            partFile().exists() shouldBe false
            coVerify(exactly = 0) { downloadDao.setFileStatus(AUDIO_FILE_ID, DownloadStatus.ERROR) }
        }

    @Test
    fun `an ordinary file fetches into its target, which survives its own success`() =
        runTest {
            var mediaTarget: File? = null
            coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } coAnswers {
                mediaTarget = secondArg()
                secondArg<File>().writeBytes(ByteArray(MEDIA_BYTES.toInt()))
                MEDIA_BYTES
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // The rule is the *sidecar's*, and this difference is the whole reason for stating it
            // in one place: a video has no second file to sweep, and sweeping its target would
            // delete the download the user just waited for.
            val target = requireNotNull(mediaTarget)
            target.name shouldNotContain DownloadQueue.PART_SUFFIX
            target.exists() shouldBe true
            // Its sidecar meanwhile kept the m4a and lost the mkv, in the same drain.
            sidecarFile().length() shouldBe SIDECAR_BYTES
            partFile().exists() shouldBe false
        }

    // ---- helpers --------------------------------------------------------------------------------

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
            // What the server actually sends: the wanted audio track wrapped in junk video.
            secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
            FETCH_BYTES
        }
        queueWith(download(quality = DownloadQuality.MEDIUM, bakedAudioStreamIndex = 1))
    }

    /** The sidecar the row names, and the mkv its fetch is written to. */
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
            transactionRunner = DownloadFixtures.directTransactionRunner,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun queueWith(vararg downloads: DownloadEntity) {
        val queued = downloads.map { DownloadWithFiles(download = it, files = emptyList()) }
        coEvery { downloadDao.nextRunnable() } returnsMany (queued + null)
    }

    private companion object {
        const val IMAGE_URL = "https://server/image"
        const val TRANSCODE_URL = "https://server/videos/stream.mkv"

        /** The extra audio language's fetch — `/Videos` with a junk video track. */
        const val AUDIO_URL = "https://server/videos/stream.mkv?audioStreamIndex=2"

        /** The `AUDIO` row is third in plan order (poster, media, sidecar). */
        const val AUDIO_FILE_ID = 3L

        /** What the fetch weighs (audio *and* junk video) against what the sidecar keeps. */
        const val FETCH_BYTES = 900L
        const val SIDECAR_BYTES = 400L

        /** What a media file's own transfer writes, straight into the target it keeps. */
        const val MEDIA_BYTES = 2_000L

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
