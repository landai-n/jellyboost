package dev.jellyboost.data.downloads.offline

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.engine.MatroskaSeekIndexRepair
import io.kotest.matchers.collections.shouldBeEmpty
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
 * The gate every offline playback goes through. Two properties are pinned hardest: a `null` answer for
 * anything not *completely* on disk (which is what makes the player fall back to streaming instead of
 * showing a source error), and the `file://` URI spelling, which cannot be checked without a real path.
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

    @Test
    fun `carries the quality the row was downloaded at`() =
        runTest {
            // The cached blob describes the *source*; only this tells the player that the bytes on disk
            // are a re-encode holding one audio track and no embedded subtitles.
            stored(quality = DownloadQuality.MEDIUM, files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.quality shouldBe DownloadQuality.MEDIUM
            resolved.isTranscoded shouldBe true
        }

    @Test
    fun `an original download is not marked as transcoded`() =
        runTest {
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.quality shouldBe DownloadQuality.ORIGINAL
            resolved.isTranscoded shouldBe false
        }

    @Test
    fun `carries the audio track the transcode baked in`() =
        runTest {
            // The cached blob lists every audio stream of the source; this column is the only record of
            // which one the file on disk actually holds.
            stored(
                quality = DownloadQuality.MEDIUM,
                bakedAudioStreamIndex = 5,
                files = listOf(mediaFile(path = write("a.mkv").absolutePath)),
            )

            provider
                .get(itemId)
                .shouldNotBeNull()
                .bakedAudioStreamIndex shouldBe 5
        }

    @Test
    fun `a row written before the column existed carries no baked track`() =
        runTest {
            stored(quality = DownloadQuality.MEDIUM, files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            // `null`, not `0`: the resolver's legacy fallback keys on exactly this.
            provider
                .get(itemId)
                .shouldNotBeNull()
                .bakedAudioStreamIndex
                .shouldBeNull()
        }

    // ---- the seek index a transcoded download arrives without -----------------------------------

    @Test
    fun `the media file is made seekable before it is handed to the player`() =
        runTest {
            // A transcoded download lands without a SeekHead, and every seek into one restarts it from
            // zero. This is the only gate that reaches downloads already on the device.
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

    // ---- extra audio tracks ---------------------------------------------------------------------

    @Test
    fun `offers the downloaded audio sidecars sorted ascending by stream index`() =
        runTest {
            val german = write("audio.5.ger.m4a")
            val french = write("audio.2.fre.m4a")
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        // Deliberately out of order: the player's `MergingMediaSource` order is the
                        // contract, not whatever order Room returned the rows in.
                        audioFile(id = 2L, streamIndex = 5, path = german.absolutePath),
                        audioFile(id = 3L, streamIndex = 2, path = french.absolutePath),
                    ),
            )

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.audio shouldContainExactly
                listOf(
                    DownloadedAudio(streamIndex = 2, uri = "file://${french.absolutePath}"),
                    DownloadedAudio(streamIndex = 5, uri = "file://${german.absolutePath}"),
                )
        }

    @Test
    fun `withholds an extra audio track whose sidecar failed or vanished`() =
        runTest {
            val present = write("audio.2.fre.m4a")
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        audioFile(id = 2L, streamIndex = 2, path = present.absolutePath),
                        audioFile(id = 3L, streamIndex = 5, path = File(storage, "gone.m4a").absolutePath),
                    ),
            )

            // "Optional-file failure → item still playable" — with one fewer language on offer.
            provider
                .get(itemId)
                .shouldNotBeNull()
                .audio
                .shouldContainExactly(
                    listOf(DownloadedAudio(streamIndex = 2, uri = "file://${present.absolutePath}")),
                )
        }

    @Test
    fun `drops an audio row with no stream index`() =
        runTest {
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        audioFile(id = 2L, streamIndex = null, path = write("audio.orphan.m4a").absolutePath),
                    ),
            )

            provider
                .get(itemId)
                .shouldNotBeNull()
                .audio
                .shouldContainExactly(emptyList())
        }

    @Test
    fun `an original download has no extra audio sidecars`() =
        runTest {
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            provider
                .get(itemId)
                .shouldNotBeNull()
                .audio
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
                        // whatever order Room returned the rows in.
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

    // ---- music --------------------------------------------------------------------------------

    @Test
    fun `a downloaded track is offered exactly like a downloaded video`() =
        runTest {
            // Nothing in this class is kind-aware: `MusicStreamResolver` asks this provider first for
            // every track it plays, so an assumption that a download is a video would silently send
            // offline music to the server.
            val track = File(storage, "04 - Go Your Own Way.flac").also { it.writeText("flac") }
            every { itemMapper.toDtoOrNull(any()) } returns
                DownloadFixtures.track(id = itemId, runTimeTicks = 21_000_000_000L)
            stored(mediaSourceId = "source-$itemId", files = listOf(mediaFile(path = track.path)))

            val media = provider.get(itemId).shouldNotBeNull()

            // Percent-encoded, which matters more for music: a track's file name is the server's own
            // ("04 - Go Your Own Way.flac") and is full of spaces.
            media.mediaUri shouldBe "file://${storage.path}/04%20-%20Go%20Your%20Own%20Way.flac"
            media.runTimeTicks shouldBe 21_000_000_000L
            media.quality shouldBe DownloadQuality.ORIGINAL
            media.subtitles.shouldBeEmpty()
            media.audio.shouldBeEmpty()
            media.trickplay.shouldBeNull()
        }

    @Test
    fun `the seek-index repair is still offered a track, and declines it as not Matroska`() =
        runTest {
            val track = File(storage, "04 - Go Your Own Way.flac").also { it.writeText("flac") }
            every { itemMapper.toDtoOrNull(any()) } returns DownloadFixtures.track(id = itemId)
            stored(mediaSourceId = "source-$itemId", files = listOf(mediaFile(path = track.path)))

            provider.get(itemId).shouldNotBeNull()

            // Called unconditionally, which is fine: the repair's first veto is "not Matroska", so a
            // flac costs two reads of twelve bytes and is left byte-for-byte alone.
            verify(exactly = 1) { seekIndex.ensureSeekable(File(track.path), any()) }
        }

    private fun stored(
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
        mediaSourceId: String = "source-1",
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        bakedAudioStreamIndex: Int? = null,
        files: List<DownloadFileEntity>,
    ) {
        coEvery { downloadDao.getWithFiles(itemId) } returns
            DownloadWithFiles(
                download =
                    DownloadFixtures
                        .download(
                            itemId = itemId,
                            status = status,
                            quality = quality,
                            bakedAudioStreamIndex = bakedAudioStreamIndex,
                        ).copy(mediaSourceId = mediaSourceId),
                files = files,
            )
    }

    @Test
    fun `attached fonts on disk are carried through with their names`() =
        runTest {
            val face = write("font.4.Face.ttf")
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        fontFile(id = 2L, fileName = "font.4.Face.ttf", path = face.absolutePath),
                    ),
            )

            val resolved = provider.get(itemId).shouldNotBeNull()

            resolved.fonts.map { it.name } shouldContainExactly listOf("font.4.Face.ttf")
            // A path, not a URI: these bytes are read by the app and handed to libass, never opened
            // by ExoPlayer.
            resolved.fonts.map { it.path } shouldContainExactly listOf(face.absolutePath)
        }

    @Test
    fun `a font whose file left the disk is dropped rather than handed over`() =
        runTest {
            stored(
                files =
                    listOf(
                        mediaFile(path = write("a.mkv").absolutePath),
                        fontFile(id = 2L, fileName = "font.4.Gone.ttf", path = "/tmp/gone.ttf"),
                    ),
            )

            provider
                .get(itemId)
                .shouldNotBeNull()
                .fonts
                .shouldBeEmpty()
        }

    @Test
    fun `an item with no attached fonts reports none`() =
        runTest {
            stored(files = listOf(mediaFile(path = write("a.mkv").absolutePath)))

            provider
                .get(itemId)
                .shouldNotBeNull()
                .fonts
                .shouldBeEmpty()
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

    private fun audioFile(
        id: Long,
        streamIndex: Int?,
        path: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ) = DownloadFixtures.file(
        id = id,
        itemId = itemId,
        type = DownloadFileType.AUDIO,
        status = status,
        path = path,
        streamIndex = streamIndex,
    )

    private fun fontFile(
        id: Long,
        fileName: String,
        path: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ) = DownloadFixtures.file(
        id = id,
        itemId = itemId,
        type = DownloadFileType.FONT,
        status = status,
        path = path,
        fileName = fileName,
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
