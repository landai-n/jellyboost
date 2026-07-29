package dev.jellyfinnative.data.downloads.plan

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.season
import dev.jellyfinnative.data.downloads.DownloadFixtures.series
import dev.jellyfinnative.data.downloads.DownloadFixtures.subtitleStream
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    fun `quality changes the media file and nothing else`() {
        val item = movie(backdropTag = "backdrop", streams = listOf(subtitleStream(index = 3)))

        val original = planner.plan(item, DIRECTORY)
        val transcoded = planner.plan(item, DIRECTORY, quality = DownloadQuality.HIGH)

        original.filterNot { it.type == DownloadFileType.MEDIA } shouldContainExactly
            transcoded.filterNot { it.type == DownloadFileType.MEDIA }
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
    fun `embedded subtitle streams are skipped`() {
        // An embedded track travels inside the media file; fetching it separately would be a
        // second copy of bytes we already have.
        val plan = planner.plan(movie(streams = listOf(subtitleStream(index = 3, external = false))), DIRECTORY)

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
    fun `a subtitle with no declared language is filed under und`() {
        val plan = planner.plan(movie(streams = listOf(subtitleStream(index = 3, language = null))), DIRECTORY)

        plan.of(DownloadFileType.SUBTITLE)!!.fileName shouldBe "subtitle.3.und.srt"
    }

    @Test
    fun `an item with no media source plans no subtitles`() {
        val plan = planner.plan(movie(mediaSourceId = null), DIRECTORY)

        plan.filter { it.type == DownloadFileType.SUBTITLE }.shouldBeEmpty()
        // …but still plans the media file: the download endpoint needs no media source.
        plan.media().url shouldBe "download://${uuid(1)}"
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
    }
}

/** A [DownloadUrlFactory] whose output encodes its inputs, so assertions read as expectations. */
private class FakeDownloadUrlFactory : DownloadUrlFactory {
    override fun mediaUrl(itemId: UUID) = "download://$itemId"

    override fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ) = "stream://$itemId?mediaSourceId=$mediaSourceId"

    override fun transcodedVideoUrl(
        itemId: UUID,
        mediaSourceId: String?,
        quality: DownloadQuality,
    ) = "transcode://$itemId?mediaSourceId=$mediaSourceId&quality=${quality.name}" +
        "&videoBitRate=${quality.videoBitRate}&maxHeight=${quality.maxHeight}"

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
}
