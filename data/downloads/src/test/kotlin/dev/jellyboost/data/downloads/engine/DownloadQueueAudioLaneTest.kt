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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/**
 * Unit tests for the second lane [DownloadQueue] drains an item's audio sidecars on
 * (DECISIONS.md, 2026-07-31, "Audio sidecars fetch concurrently with the media file").
 *
 * Separate from [DownloadQueueTest], which owns the transfer itself and already owns what a sidecar
 * *is* — the un-resumable fetch, the strip, the row's bytes. What lives here is only what having
 * **two** lanes changed: that they overlap at all, that the audio one stays sequential inside
 * itself, and that neither of the two failures they can suffer costs the other lane anything it did
 * not cost before.
 *
 * The lane tests gate on a [CompletableDeferred] the other lane completes, with a virtual
 * [LANE_TIMEOUT_MILLIS] around the wait: a queue that went back to draining sidecars after the media
 * file fails these at once rather than hanging them until `runTest` gives up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueAudioLaneTest {
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

    /** The sidecar path is the one place the queue touches real files. */
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
        every { urls.transcodedVideoUrl(any(), any(), any(), any()) } returns TRANSCODE_URL
        coEvery { downloader.download(any(), any(), any(), any(), any(), any()) } returns 100L
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { downloadDao.markDownloadingIfRunnable(any(), any()) } returns 1
        coEvery { seeder.seedFor(any(), any(), any(), any(), any()) } returns null
        coEvery { sweeper.sweep() } returns 0L
        givenLanguages(2)
    }

    // ---- the overlap itself ----------------------------------------------------------------------

    @Test
    fun `a sidecar's fetch starts before the media file has finished`() =
        runTest {
            // The whole point of the lane: a sidecar is an encode running at its own stream's
            // bitrate, so draining it afterwards added its full duration to the item's — about
            // eleven minutes of it, for two tracks, on the first device walk.
            val order = mutableListOf<String>()
            val sidecarStarted = CompletableDeferred<Unit>()
            coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } coAnswers {
                order += "media starts"
                // One lane could never reach the next line: the sidecar's row would still be
                // waiting for this very call to return.
                withTimeout(LANE_TIMEOUT_MILLIS) { sidecarStarted.await() }
                order += "media ends"
                100L
            }
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                order += "sidecar starts"
                sidecarStarted.complete(Unit)
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                FETCH_BYTES
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            order shouldContainExactly listOf("media starts", "sidecar starts", "media ends")
        }

    @Test
    fun `the sidecars of one item are fetched one after another, in stream order`() =
        runTest {
            // Two live encodes per item and no more: the lane's own rows are sequential, so the
            // server's CPU stays on the media transcode the user is waiting for. The order is the
            // plan's — ascending stream index, the same order the player merges them in.
            givenLanguages(3)
            val fetched = mutableListOf<String>()
            var inFlight = 0
            var mostAtOnce = 0
            coEvery {
                downloader.download(match { it.contains("audioStreamIndex") }, any(), any(), any(), any(), any())
            } coAnswers {
                fetched += firstArg<String>()
                inFlight++
                mostAtOnce = maxOf(mostAtOnce, inFlight)
                // A real fetch suspends constantly; a second sidecar would take any of these gaps.
                repeat(SUSPENSIONS_PER_FETCH) { yield() }
                inFlight--
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                FETCH_BYTES
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            mostAtOnce shouldBe 1
            fetched shouldContainExactly listOf(AUDIO_URL, THIRD_AUDIO_URL)
        }

    @Test
    fun `two lanes reporting at once still add up to the item's own total`() =
        runTest {
            coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } coAnswers {
                val onProgress = arg<ProgressCallback>(5)
                onProgress.onProgress(1_000L, 2_000L)
                yield()
                onProgress.onProgress(2_000L, 2_000L)
                2_000L
            }
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                val onProgress = arg<ProgressCallback>(5)
                onProgress.onProgress(300L, 0L)
                yield()
                onProgress.onProgress(FETCH_BYTES, 0L)
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                FETCH_BYTES
            }

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            // The poster's 100, the film's 2 000 and the *sidecar's* 400 — never the 900 its fetch
            // wrote, and never one lane's samples landing on top of the other's.
            listener.progress.last() shouldBe (2_500L to 2_500L)
        }

    // ---- what each failure costs -----------------------------------------------------------------

    @Test
    fun `a media failure cancels the lane, and a cancelled sidecar is not a failed one`() =
        runTest {
            val sidecarStarted = CompletableDeferred<Unit>()
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                // Mid-fetch, with part of the junk video already on disk, when the film dies.
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                sidecarStarted.complete(Unit)
                awaitCancellation()
            }
            coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } coAnswers {
                withTimeout(LANE_TIMEOUT_MILLIS) { sidecarStarted.await() }
                throw DownloadHttpException(code = 404, url = TRANSCODE_URL)
            }

            queue().drain(listener) shouldBe DrainOutcome.INCOMPLETE

            // The item fails exactly as it did before there was a lane to cancel.
            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.ERROR, NOW, any()) }
            // ERROR here would be a verdict on a row that was never allowed to finish, and the
            // retry re-plans rows rather than clearing error messages off them.
            coVerify(exactly = 0) { downloadDao.setFileStatus(AUDIO_FILE_ID, DownloadStatus.ERROR) }
            // Its fetch cannot be resumed, so leaving it would be a junk video sitting in the item's
            // directory until an attempt that truncates it anyway.
            partFile(2).exists() shouldBe false
            extractor.calls.shouldBeEmpty()
        }

    @Test
    fun `a sidecar that fails costs its own row and not the language behind it`() =
        runTest {
            givenLanguages(3)
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } throws
                IOException("the transcode died")

            queue().drain(listener) shouldBe DrainOutcome.COMPLETED

            coVerify { downloadDao.setFileStatus(AUDIO_FILE_ID, DownloadStatus.ERROR) }
            // The lane carries on, and the media file never knew: a film missing one dub is still a
            // film, and it is still missing only the one.
            coVerify { downloader.download(THIRD_AUDIO_URL, any(), any(), any(), any(), any()) }
            coVerify { downloadDao.setFileStatus(THIRD_AUDIO_FILE_ID, DownloadStatus.DOWNLOADED) }
            coVerify { downloadDao.setStatus(uuid(1), DownloadStatus.DOWNLOADED, NOW, null) }
        }

    @Test
    fun `a pause takes both lanes with it`() =
        runTest {
            // Nothing may outlive the item: the lane is a child of the transfer's own scope, not a
            // job on a scope of its own that a cancelled drain would leave running.
            val sidecarStarted = CompletableDeferred<Unit>()
            coEvery { downloader.download(AUDIO_URL, any(), any(), any(), any(), any()) } coAnswers {
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                sidecarStarted.complete(Unit)
                awaitCancellation()
            }
            coEvery { downloader.download(TRANSCODE_URL, any(), any(), any(), any(), any()) } coAnswers {
                withTimeout(LANE_TIMEOUT_MILLIS) { sidecarStarted.await() }
                awaitCancellation()
            }

            val drain = async { queue().drain(listener) }
            runCurrent()
            drain.cancelAndJoin()

            // Both were genuinely in flight when the pause landed — otherwise the rest of this
            // asserts nothing about a lane that never ran.
            sidecarStarted.isCompleted shouldBe true
            coVerify { downloadDao.requeueIfDownloading(uuid(1), NOW) }
            coVerify(exactly = 0) { downloadDao.setFileStatus(AUDIO_FILE_ID, DownloadStatus.ERROR) }
            partFile(2).exists() shouldBe false
        }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * A transcoded film with [count] audio languages, the first of them baked into the media file —
     * so the plan carries an `AUDIO` row for every stream index from 2 up, and the queue has a lane
     * with something in it.
     */
    private fun givenLanguages(count: Int) {
        val streams = (1..count).map { audioStream(index = it) }
        every { itemMapper.toDtoOrNull(any()) } returns movie(streams = streams, defaultAudioStreamIndex = 1)
        (2..count).forEach { index ->
            val url = audioUrl(index)
            every { urls.audioStreamUrl(uuid(1), "source-1", index) } returns url
            coEvery { downloader.download(url, any(), any(), any(), any(), any()) } coAnswers {
                secondArg<File>().writeBytes(ByteArray(FETCH_BYTES.toInt()))
                FETCH_BYTES
            }
        }
        queueWith(download(quality = DownloadQuality.MEDIUM, bakedAudioStreamIndex = 1))
    }

    /** The mkv one sidecar's fetch is written to, beside the sidecar it becomes. */
    private fun partFile(index: Int) = File(directory, "audio.$index.eng.m4a${DownloadQueue.PART_SUFFIX}")

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

    /** The strip stage, without a `Looper`, a muxer or a device. */
    private class FakeExtractor : AudioSidecarExtractor {
        val calls = mutableListOf<Pair<File, File>>()

        override suspend fun extract(
            source: File,
            target: File,
        ) {
            calls += source to target
            target.writeBytes(ByteArray(SIDECAR_BYTES.toInt()))
        }
    }

    /** Records what the worker would have shown in its notification. */
    private class RecordingListener : DownloadQueueListener {
        val progress = mutableListOf<Pair<Long, Long>>()

        override suspend fun onProgress(
            download: DownloadEntity,
            bytesDownloaded: Long,
            bytesTotal: Long,
        ) {
            progress += bytesDownloaded to bytesTotal
        }

        override suspend fun onIdle() = Unit
    }

    private companion object {
        const val IMAGE_URL = "https://server/image"
        const val TRANSCODE_URL = "https://server/videos/stream.mkv"

        /** An extra audio language's fetch — `/Videos` with a junk video track. */
        fun audioUrl(streamIndex: Int) = "https://server/videos/stream.mkv?audioStreamIndex=$streamIndex"

        val AUDIO_URL = audioUrl(2)
        val THIRD_AUDIO_URL = audioUrl(3)

        /** The `AUDIO` rows follow the plan (poster, media, sidecars) — so third, then fourth. */
        const val AUDIO_FILE_ID = 3L
        const val THIRD_AUDIO_FILE_ID = 4L

        /** What a fetch weighs (audio *and* junk video) against what its sidecar keeps. */
        const val FETCH_BYTES = 900L
        const val SIDECAR_BYTES = 400L

        /**
         * How long a lane waits for the other one before the test calls the overlap broken.
         *
         * Virtual milliseconds — `runTest` never spends them — so a regression fails immediately.
         */
        const val LANE_TIMEOUT_MILLIS = 1_000L

        /** How often a faked fetch hands the scheduler every other runnable coroutine. */
        const val SUSPENSIONS_PER_FETCH = 3

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
