package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.album
import dev.jellyboost.data.downloads.DownloadFixtures.artist
import dev.jellyboost.data.downloads.DownloadFixtures.audioStream
import dev.jellyboost.data.downloads.DownloadFixtures.episode
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.playlist
import dev.jellyboost.data.downloads.DownloadFixtures.season
import dev.jellyboost.data.downloads.DownloadFixtures.series
import dev.jellyboost.data.downloads.DownloadFixtures.subtitleStream
import dev.jellyboost.data.downloads.DownloadFixtures.track
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DownloadFilePlanner] — the piece of M7 most likely to fail as a 404 halfway
 * through a 2 GB transfer, and the reason [DownloadUrlFactory] is a seam at all.
 *
 * Two properties matter beyond "the right URLs": the plan's **order** (artwork first so the queue
 * row has a poster, media second because it is the point) and its **essential/optional split**,
 * which is what decides whether a failure loses the item or just its backdrop.
 */
class DownloadFilePlannerTest {
    private val urls = FakeDownloadUrlFactory()
    private val planner = DownloadFilePlanner(urls)

    // ---- order and essentials -------------------------------------------------------------------

    @Test
    fun `the primary image comes before the media file`() {
        val plan = planner.plan(movie(), DIRECTORY)

        // Artwork first is deliberate: the queue row and the notification get a poster within a
        // second instead of after the two gigabytes.
        plan.map { it.type }.take(2) shouldContainExactly
            listOf(DownloadFileType.IMAGE_PRIMARY, DownloadFileType.MEDIA)
    }

    @Test
    fun `only the media file is essential`() {
        val plan =
            planner.plan(
                movie(backdropTag = "backdrop", streams = listOf(subtitleStream(index = 3))),
                DIRECTORY,
            )

        plan.filter { it.essential }.map { it.type } shouldContainExactly listOf(DownloadFileType.MEDIA)
    }

    @Test
    fun `an item with no artwork at all still plans its media file`() {
        val plan = planner.plan(movie(primaryTag = null), DIRECTORY)

        plan.map { it.type } shouldContainExactly listOf(DownloadFileType.MEDIA)
    }

    // ---- folders are not files --------------------------------------------------------------------

    @Test
    fun `a season is refused before a URL is ever built`() {
        // `/Items/{seasonId}/Download` answers 400, which reached the user as "The server couldn't
        // send this download (error 400)". Callers expand a container into its episodes; this is
        // the guard that makes forgetting fail here, with a reason, instead of there.
        shouldThrow<NotDownloadableException> { planner.plan(season(), DIRECTORY) }
    }

    @Test
    fun `a series is refused too`() {
        shouldThrow<NotDownloadableException> { planner.plan(series(), DIRECTORY) }
    }

    @Test
    fun `an item the server flags as a folder is refused whatever its type says`() {
        shouldThrow<NotDownloadableException> {
            planner.plan(movie().copy(isFolder = true), DIRECTORY)
        }
    }

    @Test
    fun `a movie with no media source at all is still planned`() {
        // "No media sources" is not the same question as "is a folder": a lean field set drops them
        // from a perfectly downloadable film.
        planner.plan(movie(mediaSourceId = null), DIRECTORY).media().url shouldBe "download://${uuid(1)}"
    }

    // ---- the media file -------------------------------------------------------------------------

    @Test
    fun `the media file uses the dedicated download endpoint by default`() {
        val plan = planner.plan(movie(), DIRECTORY)

        plan.media().url shouldBe "download://${uuid(1)}"
    }

    @Test
    fun `a denied download policy falls back to the static video stream`() {
        val plan = planner.plan(movie(), DIRECTORY, downloadAllowed = false)

        plan.media().url shouldBe "stream://${uuid(1)}?mediaSourceId=source-1"
    }

    @Test
    fun `the media file is named after the server's own file`() {
        planner.plan(movie(), DIRECTORY).media().fileName shouldBe "Arrival.2016.mkv"
    }

    // ---- download quality (M9) ------------------------------------------------------------------

    @Test
    fun `a quality below the original asks for a transcode instead of the download endpoint`() {
        val plan = planner.plan(movie(), DIRECTORY, quality = DownloadQuality.MEDIUM)

        plan.media().url shouldBe
            "transcode://${uuid(1)}?mediaSourceId=source-1&quality=MEDIUM&videoBitRate=8000000&maxHeight=1080"
    }

    @Test
    fun `every transcoded step carries its own bitrate and height`() {
        DownloadQuality.entries.filter { it.isTranscoded }.forEach { quality ->
            val url = planner.plan(movie(), DIRECTORY, quality = quality).media().url

            url shouldContain "videoBitRate=${quality.videoBitRate}"
            url shouldContain "maxHeight=${quality.maxHeight}"
        }
    }

    @Test
    fun `a denied download policy does not downgrade a transcode to the static stream`() {
        // The static stream *is* the original file; falling back to it would silently hand the user
        // the 25 Mbps remux they asked the server to shrink.
        val plan = planner.plan(movie(), DIRECTORY, downloadAllowed = false, quality = DownloadQuality.LOW)

        plan.media().url shouldContain "transcode://"
    }

    @Test
    fun `a transcoded media file is named for the container the server actually sends`() {
        // What arrives is a Matroska mux of a fresh H.264/AAC encode, not the source file, and the
        // name says so — along with the quality, so a re-download at another quality cannot land on
        // top of this file. `mp4` was tried first and produced a file Media3 refuses to open; see
        // `DownloadQuality.CONTAINER`.
        planner.plan(movie(), DIRECTORY, quality = DownloadQuality.LOW).media().fileName shouldBe
            "$DIRECTORY (low).${DownloadQuality.CONTAINER}"
        DownloadQuality.CONTAINER shouldBe "mkv"
    }

    @Test
    fun `quality changes the media file and the embedded-subtitle sidecars, and nothing else`() {
        val item = movie(backdropTag = "backdrop", streams = listOf(subtitleStream(index = 3)))

        val original = planner.plan(item, DIRECTORY)
        val transcoded = planner.plan(item, DIRECTORY, quality = DownloadQuality.HIGH)

        // The item's one subtitle here is genuinely external, so it is fetched at either quality —
        // artwork and tiles are quality-independent, and this pins that they stay so.
        original.filterNot { it.type == DownloadFileType.MEDIA } shouldContainExactly
            transcoded.filterNot { it.type == DownloadFileType.MEDIA }
    }

    // ---- the baked audio track (schema v7) ------------------------------------------------------

    @Test
    fun `a transcode names the audio track it wants baked in`() {
        // `/Videos/{id}/stream.mkv` takes exactly one audioStreamIndex and drops every other track.
        // Naming it is what lets the download row record which one survived.
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1), audioStream(index = 2)), defaultAudioStreamIndex = 2),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.media().url shouldContain "audioStreamIndex=2"
    }

    @Test
    fun `a transcode falls back to the first audio stream when the item declares no default`() {
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1), audioStream(index = 2))),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.media().url shouldContain "audioStreamIndex=1"
    }

    @Test
    fun `a default that names no audio stream is ignored rather than passed on`() {
        // A stale or subtitle-shaped index would ask the server to encode a track that is not audio.
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1)), defaultAudioStreamIndex = 9),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.media().url shouldContain "audioStreamIndex=1"
    }

    @Test
    fun `an item with no audio streams asks for no audio track at all`() {
        val plan = planner.plan(movie(), DIRECTORY, quality = DownloadQuality.MEDIUM)

        plan.media().url shouldNotContain "audioStreamIndex"
    }

    @Test
    fun `an original download names no audio track, because it keeps them all`() {
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1)), defaultAudioStreamIndex = 1),
                DIRECTORY,
            )

        plan.media().url shouldBe "download://${uuid(1)}"
    }

    @Test
    fun `an explicit audio index overrides the item's own default`() {
        // The seam a preferred-audio-language choice enters through: only the download row would
        // remember it, and only the row can hand it back on a later re-plan.
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1), audioStream(index = 2)), defaultAudioStreamIndex = 1),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
                audioStreamIndex = 2,
            )

        plan.media().url shouldContain "audioStreamIndex=2"
    }

    // ---- images ---------------------------------------------------------------------------------

    @Test
    fun `the backdrop is requested wider than the poster`() {
        val plan = planner.plan(movie(backdropTag = "backdrop-tag"), DIRECTORY)

        plan.of(DownloadFileType.IMAGE_PRIMARY)!!.url shouldContain "w=${DownloadFilePlanner.PRIMARY_IMAGE_WIDTH}"
        plan.of(DownloadFileType.IMAGE_BACKDROP)!!.url shouldContain "w=${DownloadFilePlanner.BACKDROP_IMAGE_WIDTH}"
    }

    @Test
    fun `an episode also fetches its series poster`() {
        val plan = planner.plan(episode(), DIRECTORY)

        val series = plan.of(DownloadFileType.IMAGE_SERIES_PRIMARY).shouldNotBeNull()
        // Requested for the *series* id, not the episode's — that is what makes the offline library
        // able to render the show for an episode that was downloaded on its own.
        series.url shouldContain uuid(10).toString()
        series.fileName shouldBe "series-primary.webp"
    }

    @Test
    fun `an episode whose series has no artwork plans no series image`() {
        val plan = planner.plan(episode(seriesPrimaryImageTag = null), DIRECTORY)

        plan.of(DownloadFileType.IMAGE_SERIES_PRIMARY).shouldBeNull()
    }

    // ---- subtitles ------------------------------------------------------------------------------

    @Test
    fun `external text subtitles are planned one file per stream`() {
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            subtitleStream(index = 3, codec = "subrip", language = "eng"),
                            subtitleStream(index = 4, codec = "ass", language = "fra"),
                        ),
                ),
                DIRECTORY,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.map { it.fileName } shouldContainExactly
            listOf("subtitle.3.eng.srt", "subtitle.4.fra.ass")
    }

    @Test
    fun `an original download skips embedded subtitle streams`() {
        // The file it is about to fetch *is* the source, embedded track and all; a sidecar would be
        // a second copy of bytes we already have — and a second route to one picker entry.
        val plan = planner.plan(movie(streams = listOf(subtitleStream(index = 3, external = false))), DIRECTORY)

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
    }

    @Test
    fun `a transcode fetches a sidecar for every embedded text subtitle`() {
        // The transcoder maps at most one subtitle and drops the rest, so on this path the sidecar
        // is the only copy there will ever be (docs/notes/offline-multitrack-design.md, phase 0).
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            subtitleStream(index = 6, codec = "subrip", language = "fra", external = false),
                            subtitleStream(index = 7, codec = "subrip", language = "fra", external = false),
                        ),
                ),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.map { it.fileName } shouldContainExactly
            listOf("subtitle.6.fra.srt", "subtitle.7.fra.srt")
    }

    @Test
    fun `a stream the server will not hand over separately is never asked for`() {
        // `supportsExternalStream = false` is the server saying it cannot extract this one; asking
        // anyway is a 404 the queue would log for every optional file of every download.
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(subtitleStream(index = 3, external = false, supportsExternalStream = false)),
                ),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
    }

    @Test
    fun `bitmap subtitle formats are skipped`() {
        // ExoPlayer cannot play PGS or VobSub from a standalone sidecar file, so downloading one
        // would produce a track that exists and never renders.
        val plan = planner.plan(movie(streams = listOf(subtitleStream(index = 3, codec = "pgssub"))), DIRECTORY)

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
    }

    @Test
    fun `a bitmap subtitle stays skipped at a transcoded quality, embedded or not`() {
        // The one genuine casualty of a transcode: no OCR, and no side-loading a `.sup`. It survives
        // in an ORIGINAL download and nowhere else.
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            subtitleStream(index = 8, codec = "pgssub", external = false),
                            subtitleStream(index = 9, codec = "dvdsub", external = true),
                        ),
                ),
                DIRECTORY,
                quality = DownloadQuality.LOW,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
    }

    @Test
    fun `an external sidecar is fetched at every quality`() {
        DownloadQuality.entries.forEach { quality ->
            val plan =
                planner.plan(
                    movie(streams = listOf(subtitleStream(index = 3, language = "eng"))),
                    DIRECTORY,
                    quality = quality,
                )

            plan.filter { it.type == DownloadFileType.SUBTITLE }.map { it.fileName } shouldContainExactly
                listOf("subtitle.3.eng.srt")
        }
    }

    @Test
    fun `a subtitle with no declared language is filed under und`() {
        val plan = planner.plan(movie(streams = listOf(subtitleStream(index = 3, language = null))), DIRECTORY)

        plan.of(DownloadFileType.SUBTITLE)!!.fileName shouldBe "subtitle.3.und.srt"
    }

    @Test
    fun `a hostile language tag cannot name a path outside the item directory`() {
        // DL-15: `MediaStream.language` is the raw container track tag from ffprobe — whoever
        // supplies media to the library controls it. Interpolated verbatim, `../` reached
        // `File(dir, fileName)` with the downloader running mkdirs() on the parent: a write
        // outside the downloads root that no sweep or delete ever collects.
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            subtitleStream(index = 3, language = "../../evil/tag"),
                            audioStream(index = 5, language = "fra/../../x"),
                            audioStream(index = 6, language = "eng"),
                        ),
                ),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
                audioStreamIndex = 6,
            )

        plan.of(DownloadFileType.SUBTITLE)!!.fileName shouldBe "subtitle.3.eviltag.srt"
        plan.of(DownloadFileType.AUDIO)!!.fileName shouldBe "audio.5.frax.m4a"
    }

    @Test
    fun `an overlong language tag is bounded rather than failing the file name`() {
        val plan =
            planner.plan(
                movie(streams = listOf(subtitleStream(index = 3, language = "x".repeat(500)))),
                DIRECTORY,
            )

        plan.of(DownloadFileType.SUBTITLE)!!.fileName shouldBe "subtitle.3.${"x".repeat(20)}.srt"
    }

    @Test
    fun `a language tag that sanitises to nothing falls back to und`() {
        val plan =
            planner.plan(
                movie(streams = listOf(subtitleStream(index = 3, language = "../\\..//"))),
                DIRECTORY,
            )

        plan.of(DownloadFileType.SUBTITLE)!!.fileName shouldBe "subtitle.3.und.srt"
    }

    @Test
    fun `an item with no media source plans no subtitles`() {
        val plan = planner.plan(movie(mediaSourceId = null), DIRECTORY)

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
        // …but still plans the media file: the download endpoint needs no media source.
        plan.media().url shouldBe "download://${uuid(1)}"
    }

    // ---- audio sidecars (phase 2, docs/notes/offline-multitrack-design.md) -----------------------

    @Test
    fun `a transcode fetches a sidecar for every audio stream except the one it baked in`() {
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            audioStream(index = 1, language = "eng"),
                            audioStream(index = 2, language = "fra"),
                            audioStream(index = 3, language = "spa"),
                        ),
                ),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
                audioStreamIndex = 2,
            )

        val audioRows = plan.filter { it.type == DownloadFileType.AUDIO }
        audioRows.map { it.fileName } shouldContainExactly listOf("audio.1.eng.m4a", "audio.3.spa.m4a")
        audioRows.map { it.streamIndex } shouldContainExactly listOf(1, 3)
        audioRows.map { it.url } shouldContainExactly
            listOf(
                "audio://${uuid(1)}?mediaSourceId=source-1&audioStreamIndex=1",
                "audio://${uuid(1)}?mediaSourceId=source-1&audioStreamIndex=3",
            )
    }

    @Test
    fun `audio sidecars come after subtitle rows and before trickplay tiles`() {
        val plan =
            planner.plan(
                movie(
                    streams =
                        listOf(
                            subtitleStream(index = 5, language = "eng"),
                            audioStream(index = 1),
                            audioStream(index = 2),
                        ),
                    trickplay = trickplay(width = 320, thumbnails = 10),
                ),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
                audioStreamIndex = 1,
            )

        plan
            .map { it.type }
            .filter { it in setOf(DownloadFileType.SUBTITLE, DownloadFileType.AUDIO, DownloadFileType.TRICKPLAY_TILE) }
            .shouldContainExactly(DownloadFileType.SUBTITLE, DownloadFileType.AUDIO, DownloadFileType.TRICKPLAY_TILE)
    }

    @Test
    fun `an original download plans no audio sidecars, because it keeps them all`() {
        // Same reasoning as the embedded-subtitle guard: the file being fetched already holds every
        // track, so a sidecar would be a duplicate download and a second route to one picker entry.
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1), audioStream(index = 2)), defaultAudioStreamIndex = 1),
                DIRECTORY,
            )

        plan.filter { it.type == DownloadFileType.AUDIO }.shouldBeEmpty()
    }

    @Test
    fun `a single audio stream is the one already baked in, so it gets no sidecar`() {
        val plan =
            planner.plan(
                movie(streams = listOf(audioStream(index = 1))),
                DIRECTORY,
                quality = DownloadQuality.MEDIUM,
            )

        plan.filter { it.type == DownloadFileType.AUDIO }.shouldBeEmpty()
    }

    @Test
    fun `an item with no audio streams at all plans no audio sidecars`() {
        val plan = planner.plan(movie(), DIRECTORY, quality = DownloadQuality.MEDIUM)

        plan.filter { it.type == DownloadFileType.AUDIO }.shouldBeEmpty()
    }

    // ---- trickplay ------------------------------------------------------------------------------

    @Test
    fun `trickplay tile count is derived from the thumbnail count and grid`() {
        // 250 thumbnails at 10x10 per sheet is 3 sheets — the last one only partly filled.
        val plan = planner.plan(movie(trickplay = trickplay(width = 320, thumbnails = 250)), DIRECTORY)

        plan.filter { it.type == DownloadFileType.TRICKPLAY_TILE }.map { it.tileIndex } shouldContainExactly
            listOf(0, 1, 2)
    }

    @Test
    fun `only the widest trickplay resolution is downloaded`() {
        val plan =
            planner.plan(
                movie(
                    trickplay =
                        mapOf(
                            "source-1" to
                                mapOf(
                                    "320" to info(width = 320, thumbnails = 100),
                                    "640" to info(width = 640, thumbnails = 100),
                                ),
                        ),
                ),
                DIRECTORY,
            )

        val tiles = plan.filter { it.type == DownloadFileType.TRICKPLAY_TILE }
        tiles.map { it.tileWidth }.distinct() shouldContainExactly listOf(640)
    }

    @Test
    fun `an item with no trickplay plans no tiles`() {
        planner.plan(movie(), DIRECTORY).filter { it.type == DownloadFileType.TRICKPLAY_TILE }.shouldBeEmpty()
    }

    @Test
    fun `a trickplay entry with no thumbnails plans no tiles`() {
        val plan = planner.plan(movie(trickplay = trickplay(width = 320, thumbnails = 0)), DIRECTORY)

        plan.filter { it.type == DownloadFileType.TRICKPLAY_TILE }.shouldBeEmpty()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun List<PlannedFile>.media() = single { it.type == DownloadFileType.MEDIA }

    private fun List<PlannedFile>.of(type: DownloadFileType) = firstOrNull { it.type == type }

    private fun trickplay(
        width: Int,
        thumbnails: Int,
    ) = mapOf("source-1" to mapOf(width.toString() to info(width, thumbnails)))

    // ---- music (M13 Phase 5) --------------------------------------------------------------------

    @Test
    fun `a track plans exactly two files, artwork then the original`() {
        val plan = planner.plan(track(), TRACK_DIRECTORY)

        // The whole audio branch in one assertion: no backdrop, no series poster, no subtitles, no
        // audio sidecars, no trickplay — and the same artwork-first order the video plan promises.
        plan.map { it.type } shouldContainExactly
            listOf(DownloadFileType.IMAGE_PRIMARY, DownloadFileType.MEDIA)
    }

    @Test
    fun `a track's artwork is the album's cover, not the track's own image`() {
        val plan = planner.plan(track(), TRACK_DIRECTORY)

        val art = plan.of(DownloadFileType.IMAGE_PRIMARY).shouldNotBeNull()
        // Addressed on the album id and the album's tag: every track of the album then holds the
        // same cover, which is the image the offline card draws.
        art.url shouldContain uuid(40).toString()
        art.url shouldContain "tag=album-tag"
        art.fileName shouldBe "primary.webp"
    }

    @Test
    fun `a track whose album has no cover falls back to its own primary image`() {
        val plan = planner.plan(track(albumPrimaryImageTag = null, primaryTag = "track-tag"), TRACK_DIRECTORY)

        plan.of(DownloadFileType.IMAGE_PRIMARY).shouldNotBeNull().url shouldContain "tag=track-tag"
    }

    @Test
    fun `a track with no artwork anywhere still plans its media file`() {
        val plan = planner.plan(track(albumPrimaryImageTag = null, primaryTag = null), TRACK_DIRECTORY)

        plan.map { it.type } shouldContainExactly listOf(DownloadFileType.MEDIA)
    }

    @Test
    fun `a track is fetched from the download endpoint and keeps the server's file name`() {
        val plan = planner.plan(track(), TRACK_DIRECTORY)

        plan.media().url shouldBe "download://${uuid(30)}"
        plan.media().fileName shouldBe "04 - Go Your Own Way.flac"
    }

    @Test
    fun `a track falls back to the static audio stream when downloading is not allowed`() {
        val plan = planner.plan(track(), TRACK_DIRECTORY, downloadAllowed = false)

        // `/Audio/{id}/stream?static=true`, not the video route: the same bytes over a route the
        // server does not gate on `enableContentDownloading`.
        plan.media().url shouldContain "audio-static://${uuid(30)}"
        plan.media().url shouldContain "mediaSourceId=source-${uuid(30)}"
    }

    @Test
    fun `a quality stamped on a track is ignored — music downloads are originals only`() {
        val plan = planner.plan(track(), TRACK_DIRECTORY, quality = DownloadQuality.LOW)

        // Key decision 10. `DownloadEnqueuer` never writes a transcoded audio row, and the planner
        // does not honour one either: a transcode URL here would produce a file the offline player
        // and the size machinery both describe wrongly.
        plan.media().url shouldBe "download://${uuid(30)}"
        plan.media().fileName shouldNotContain "low"
        plan.map { it.type } shouldContainExactly
            listOf(DownloadFileType.IMAGE_PRIMARY, DownloadFileType.MEDIA)
    }

    @Test
    fun `a track's subtitle-shaped streams are never turned into sidecars`() {
        // A tagged music file can carry an odd extra stream; the audio branch does not look.
        val plan =
            planner.plan(
                track(streams = listOf(subtitleStream(index = 2), audioStream(index = 1))),
                TRACK_DIRECTORY,
            )

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
        plan.filter { it.type == DownloadFileType.AUDIO }.shouldBeEmpty()
    }

    @Test
    fun `an album is refused before a URL is ever built`() {
        // Same guard a season gets: the caller expands containers, and one that forgot must fail
        // here rather than as an unexplained 400 halfway down the queue.
        shouldThrow<NotDownloadableException> { planner.plan(album(), TRACK_DIRECTORY) }
        shouldThrow<NotDownloadableException> { planner.plan(artist(), TRACK_DIRECTORY) }
        shouldThrow<NotDownloadableException> { planner.plan(playlist(), TRACK_DIRECTORY) }
    }

    private fun info(
        width: Int,
        thumbnails: Int,
    ) = TrickplayInfoDto(
        width = width,
        height = width * 9 / 16,
        tileWidth = 10,
        tileHeight = 10,
        thumbnailCount = thumbnails,
        interval = 10_000,
        bandwidth = 1_000,
    )

    private companion object {
        const val DIRECTORY = "Arrival (2016)"

        /** What `DownloadPaths.itemDirectoryName` produces for [DownloadFixtures.track]. */
        const val TRACK_DIRECTORY = "Fleetwood Mac - Rumours - 04 - Go Your Own Way"
    }
}

/** A [DownloadUrlFactory] whose output encodes its inputs, so assertions read as expectations. */
private class FakeDownloadUrlFactory : DownloadUrlFactory {
    override fun mediaUrl(itemId: UUID) = "download://$itemId"

    override fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "stream://$itemId?mediaSourceId=$mediaSourceId"

    override fun staticAudioUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "audio-static://$itemId?mediaSourceId=$mediaSourceId"

    override fun transcodedVideoUrl(
        itemId: UUID,
        mediaSourceId: String?,
        quality: DownloadQuality,
        audioStreamIndex: Int?,
    ) = "transcode://$itemId?mediaSourceId=$mediaSourceId&quality=${quality.name}" +
        "&videoBitRate=${quality.videoBitRate}&maxHeight=${quality.maxHeight}" +
        // Absent rather than `audioStreamIndex=null`, so a test can assert on either.
        audioStreamIndex?.let { "&audioStreamIndex=$it" }.orEmpty()

    override fun imageUrl(
        itemId: UUID,
        imageType: ImageType,
        tag: String,
        fillWidth: Int,
    ) = "image://$itemId?type=$imageType&tag=$tag&w=$fillWidth"

    override fun subtitleUrl(
        itemId: UUID,
        mediaSourceId: String,
        streamIndex: Int,
        format: String,
    ) = "subtitle://$itemId/$mediaSourceId/$streamIndex.$format"

    override fun trickplayTileUrl(
        itemId: UUID,
        width: Int,
        tileIndex: Int,
    ) = "trickplay://$itemId/$width/$tileIndex"

    override fun audioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        streamIndex: Int,
    ) = "audio://$itemId?mediaSourceId=$mediaSourceId&audioStreamIndex=$streamIndex"
}
