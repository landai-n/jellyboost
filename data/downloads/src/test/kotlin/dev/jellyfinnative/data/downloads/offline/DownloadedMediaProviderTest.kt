package dev.jellyfinnative.data.downloads.offline

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures
import dev.jellyfinnative.data.downloads.engine.MatroskaSeekIndexRepair
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * Unit tests for [DownloadedMediaProvider] — the gate every offline playback goes through.
 *
 * Two properties matter more than the mapping and are pinned hardest: a `null` answer for anything
 * that is not *completely* on disk (which is what makes the player fall back to streaming instead
 * of showing a source error), and the `file://` URI spelling, which is the one thing that cannot be
 * checked without a real path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedMediaProviderTest {
    private val downloadDao = mockk<DownloadDao>()
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()

    private val seekIndex = mockk<MatroskaSeekIndexRepair>(relaxed = true)

    private val provider =
        DownloadedMediaProvider(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            seekIndex = seekIndex,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val itemId: UUID = DownloadFixtures.uuid(1)

    @TempDir
    lateinit var storage: File

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.getItem(any()) } returns ITEM_ENTITY
        every { itemMapper.toDtoOrNull(any()) } returns DownloadFixtures.movie(id = itemId)
    }

    // ---- the gate -----------------------------------------------------------------------------

    @Test
    fun `an item that was never downloaded has nothing to play locally`() =
        runTest {
            coEvery { downloadDao.getWithFiles(itemId) } returns null

            provider.get(itemId).shouldBeNull()
        }

    @Test
    fun `a download still in the queue is not playable locally`() =
        runTest {
            stored(status = DownloadStatus.DOWNLOADING, files = listOf(mediaFile()))

            provider.get(itemId).shouldBeNull()
        }

    @Test
    fun `a finished download whose media file failed is not playable locally`() =
        runTest {
            stored(files = listOf(mediaFile(status = DownloadStatus.ERROR)))

            provider.get(itemId).shouldBeNull()
        }

    @Test
    fun `a finished download whose media file left the disk is not playable locally`() =
        runTest {
            // The rows survive an external storage wipe; the bytes do not. Streaming beats an
            // ExoPlayer source error several seconds into a blank screen.
            stored(
                files =
                    listOf(
                        DownloadFixtures.file(
                            id = 1L,
                            status = DownloadStatus.DOWNLOADED,
                            path = File(storage, "gone.mkv").absolutePath,
                        ),
                    ),
            )

            provider.get(itemId).shouldBeNull()
        }

    // ---- what it resolves ---------------------------------------------------------------------

    @Test
    fun `resolves the media file as an encoded file URI`() =
        runTest {
            val media = write("Arrival (2016) #1.mkv")
            stored(files = listOf(mediaFile(path = media.absolutePath)))

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.mediaUri shouldBe "file://" + media.absolutePath.replace(" ", "%20").replace("#", "%23")
            resolved.itemId shouldBe itemId
        }

    @Test
    fun `takes the runtime and the streams from the cached media source`() =
        runTest {
            every { itemMapper.toDtoOrNull(any()) } returns
                movieWith(
                    mediaSourceId = "source-1",
                    runTimeTicks = 72_000_000_000L,
                    streams = listOf(DownloadFixtures.subtitleStream(index = 3)),
                )
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.mediaSourceId shouldBe "source-1"
            resolved.runTimeTicks shouldBe 72_000_000_000L
            resolved.mediaSource
                .shouldNotBeNull()
                .mediaStreams
                ?.single()
                ?.index shouldBe 3
        }

    // ---- the seek index a transcoded download arrives without -----------------------------------

    @Test
    fun `the media file is made seekable before it is handed to the player`() =
        runTest {
            // A transcoded download lands without a SeekHead, and every seek into one restarts it
            // from zero (see MatroskaSeekIndexRepair). This is the only gate that reaches downloads
            // already on the device, so it is the one that has to ask.
            every { itemMapper.toDtoOrNull(any()) } returns
                movieWith(mediaSourceId = "source-1", runTimeTicks = 72_000_000_000L)
            val media = write("a.mkv")
            stored(files = listOf(mediaFile(path = media.absolutePath)))

            provider.get(itemId).shouldNotBeNull()

            // Milliseconds, not ticks: what a Matroska `Duration` is scaled from.
            verify { seekIndex.ensureSeekable(media, 7_200_000L) }
        }

    @Test
    fun `nothing is written to a file that is not playable anyway`() =
        runTest {
            coEvery { downloadDao.getWithFiles(itemId) } returns null

            provider.get(itemId).shouldBeNull()

            verify(exactly = 0) { seekIndex.ensureSeekable(any(), any()) }
        }

    @Test
    fun `matches the downloaded media source dash-insensitively`() =
        runTest {
            // The id the download row stored came off a PlaybackInfo-shaped response and may be
            // dash-less, while the cached item's own sources carry dashes.
            every { itemMapper.toDtoOrNull(any()) } returns
                movieWith(mediaSourceId = "aaaa-bbbb", runTimeTicks = 5L)
            stored(
                mediaSourceId = "aaaabbbb",
                files = listOf(mediaFile(path = write("a.mkv").absolutePath)),
            )

            provider.get(itemId).shouldNotBeNull().mediaSourceId shouldBe "aaaa-bbbb"
        }

    @Test
    fun `still plays an item whose cached blob can no longer be read`() =
        runTest {
            every { itemMapper.toDtoOrNull(any()) } returns null
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            val resolved = provider.get(itemId).shouldNotBeNull()

            // No tracks to offer, but the film still plays — better than refusing to open it.
            resolved.mediaSource.shouldBeNull()
            resolved.runTimeTicks shouldBe 0L
            resolved.mediaSourceId shouldBe "source-1"
        }

    // ---- optional files -----------------------------------------------------------------------

    @Test
    fun `offers the downloaded subtitle sidecars, keyed on their stream index`() =
        runTest {
            val subtitle = write("subtitle.3.eng.srt")
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        subtitleFile(id = 2L, streamIndex = 3, path = subtitle.absolutePath),
                    ),
            )

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.subtitles.single().streamIndex shouldBe 3
            resolved.subtitles.single().uri shouldBe "file://${subtitle.absolutePath}"
        }

    @Test
    fun `withholds a subtitle whose sidecar failed or vanished`() =
        runTest {
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        subtitleFile(
                            id = 2L,
                            streamIndex = 3,
                            path = write("present.srt").absolutePath,
                            status = DownloadStatus.ERROR,
                        ),
                        subtitleFile(id = 3L, streamIndex = 4, path = File(storage, "gone.srt").absolutePath),
                    ),
            )

            // "Optional-file failure → item still playable" — with one fewer language on offer.
            provider
                .get(itemId)
                .shouldNotBeNull()
                .subtitles
                .shouldContainExactly(emptyList())
        }

    // ---- trickplay ----------------------------------------------------------------------------

    @Test
    fun `exposes the downloaded trickplay tiles in tile order with the server's geometry`() =
        runTest {
            every { itemMapper.toDtoOrNull(any()) } returns
                DownloadFixtures.movie(
                    id = itemId,
                    trickplay = mapOf("source-1" to mapOf("320" to DownloadFixtures.trickplayInfo())),
                )
            val first = write("trickplay.320.0.jpg")
            val second = write("trickplay.320.1.jpg")
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        // Deliberately out of order: the scrubber indexes by tile number, not by
                        // whatever order Room happened to return the rows in.
                        tileFile(id = 3L, tileIndex = 1, path = second.absolutePath),
                        tileFile(id = 2L, tileIndex = 0, path = first.absolutePath),
                    ),
            )

            val trickplay =
                provider
                    .get(itemId)
                    .shouldNotBeNull()
                    .trickplay
                    .shouldNotBeNull()

            trickplay.tileUris shouldContainExactly
                listOf("file://${first.absolutePath}", "file://${second.absolutePath}")
            trickplay.width shouldBe 320
            trickplay.tileWidth shouldBe 10
            trickplay.tileHeight shouldBe 10
            trickplay.thumbnailCount shouldBe 250
            trickplay.intervalMs shouldBe 10_000
        }

    @Test
    fun `has no trickplay when the server never generated any`() =
        runTest {
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            provider
                .get(itemId)
                .shouldNotBeNull()
                .trickplay
                .shouldBeNull()
        }

    @Test
    fun `has no trickplay when the tiles are on the queue but not on the disk`() =
        runTest {
            every { itemMapper.toDtoOrNull(any()) } returns
                DownloadFixtures.movie(
                    id = itemId,
                    trickplay = mapOf("source-1" to mapOf("320" to DownloadFixtures.trickplayInfo())),
                )
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        tileFile(id = 2L, tileIndex = 0, path = File(storage, "gone.jpg").absolutePath),
                    ),
            )

            provider
                .get(itemId)
                .shouldNotBeNull()
                .trickplay
                .shouldBeNull()
        }

    // ---- helpers ------------------------------------------------------------------------------

    private fun stored(
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
        mediaSourceId: String = "source-1",
        files: List<DownloadFileEntity>,
    ) {
        coEvery { downloadDao.getWithFiles(itemId) } returns
            DownloadWithFiles(
                download =
                    DownloadFixtures
                        .download(itemId = itemId, status = status)
                        .copy(mediaSourceId = mediaSourceId),
                files = files,
            )
    }

    private fun mediaFile(
        path: String = "/tmp/missing.mkv",
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ) = DownloadFixtures.file(id = 1L, itemId = itemId, status = status, path = path)

    private fun subtitleFile(
        id: Long,
        streamIndex: Int,
        path: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ) = DownloadFixtures.file(
        id = id,
        itemId = itemId,
        type = DownloadFileType.SUBTITLE,
        status = status,
        path = path,
        streamIndex = streamIndex,
    )

    private fun tileFile(
        id: Long,
        tileIndex: Int,
        path: String,
    ) = DownloadFixtures.file(
        id = id,
        itemId = itemId,
        type = DownloadFileType.TRICKPLAY_TILE,
        status = DownloadStatus.DOWNLOADED,
        path = path,
        tileIndex = tileIndex,
        tileWidth = 320,
    )

    private fun movieWith(
        mediaSourceId: String,
        runTimeTicks: Long,
        streams: List<MediaStream> = emptyList(),
    ): BaseItemDto =
        DownloadFixtures.movie(id = itemId).let { movie ->
            movie.copy(
                mediaSources =
                    listOf(
                        DownloadFixtures
                            .mediaSource(id = mediaSourceId, size = 1L, streams = streams)
                            .copy(runTimeTicks = runTimeTicks),
                    ),
            )
        }

    /** Writes a real file so the provider's on-disk check has something to find. */
    private fun write(name: String): File = File(storage, name).apply { writeText("bytes") }

    private companion object {
        val ITEM_ENTITY =
            mockk<ItemEntity>(relaxed = true).also { every { it.id } returns DownloadFixtures.uuid(1) }
    }
}
