package dev.jellyboost.player.cast

import androidx.media3.common.MimeTypes
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.SubtitleSpec
import dev.jellyboost.player.model.externalSubtitleTrackId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [CastSpecMapper].
 *
 * Everything a cast session can get wrong *quietly* is decided here. A media URL without its token
 * is a receiver that shows nothing and reports nothing back; a subtitle URL without one is a
 * subtitle that never appears; a track id that is not the Jellyfin stream index is a picker entry
 * that turns the wrong language on. None of the three is visible on this device, which is why they
 * are pinned in plain data rather than left to the on-device assembly.
 */
class CastSpecMapperTest {
    /**
     * A URL factory that appends the token exactly as the SDK-backed one does, idempotence included.
     *
     * Only [StreamUrlFactory.withApiKey] matters here; the rest of the interface is inherited from
     * the real one's shape and never called.
     */
    private val urls =
        object : StreamUrlFactory {
            override fun directPlayUrl(
                itemId: UUID,
                mediaSourceId: String,
                playSessionId: String,
            ) = "https://server/Videos/$itemId/stream?static=true"

            override fun directStreamUrl(
                itemId: UUID,
                container: String,
                mediaSourceId: String,
                playSessionId: String,
            ) = "https://server/Videos/$itemId/stream.$container"

            override fun absoluteUrl(path: String) = "https://server$path"

            override fun trickplayTileUrl(
                itemId: UUID,
                width: Int,
                tileIndex: Int,
                mediaSourceId: String?,
            ) = "https://server/Videos/$itemId/Trickplay/$width/$tileIndex.jpg"

            override fun withApiKey(url: String): String {
                if (Regex("[?&]ApiKey=", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
                val separator = if (url.contains('?')) '&' else '?'
                return "$url$separator" + "ApiKey=$TOKEN"
            }
        }

    private val mapper = CastSpecMapper(urls)

    @Test
    fun `the media URL reaches the receiver with a token on it`() {
        val spec = mapper.map(itemSpec(uri = "https://server/Videos/x/stream?static=true"), directPlay())

        spec.contentId shouldBe "https://server/Videos/x/stream?static=true&ApiKey=$TOKEN"
    }

    @Test
    fun `a URL the server already signed is left alone`() {
        // The dev server (2026-07-31) returns `TranscodingUrl` and every subtitle `DeliveryUrl` with
        // `ApiKey` already on them; appending a second one would make the query ambiguous.
        val signed = "https://server/videos/x/master.m3u8?PlaySessionId=s&ApiKey=$TOKEN"

        val spec = mapper.map(itemSpec(uri = signed, mimeType = MimeTypes.APPLICATION_M3U8), transcode())

        spec.contentId shouldBe signed
    }

    @Test
    fun `every subtitle URL is signed too, since the receiver fetches those as well`() {
        val spec =
            mapper.map(
                itemSpec(
                    subtitles =
                        listOf(
                            subtitleSpec(index = 4, uri = "$SUBTITLES/4/0/Stream.vtt"),
                            subtitleSpec(index = 5, uri = "$SUBTITLES/5/0/Stream.vtt?ApiKey=$TOKEN"),
                        ),
                ),
                directPlay(),
            )

        spec.tracks.map { it.uri } shouldBe
            listOf(
                "$SUBTITLES/4/0/Stream.vtt?ApiKey=$TOKEN",
                "$SUBTITLES/5/0/Stream.vtt?ApiKey=$TOKEN",
            )
    }

    @Test
    fun `an external track id becomes the Jellyfin stream index the picker speaks`() {
        val spec =
            mapper.map(
                itemSpec(subtitles = listOf(subtitleSpec(index = 7, uri = "https://server/s.vtt"))),
                directPlay(),
            )

        // The whole point: `CastPlayerHandle.selectSubtitleTrack(index = 7)` can hand 7 straight to
        // `setActiveMediaTracks` with no translation in between.
        spec.tracks.single().id shouldBe 7
    }

    @Test
    fun `a track id that is not one of ours is dropped rather than given an invented one`() {
        val spec =
            mapper.map(
                itemSpec(
                    subtitles =
                        listOf(
                            SubtitleSpec(
                                id = "burned-in",
                                uri = "https://server/s.vtt",
                                mimeType = MimeTypes.TEXT_VTT,
                                label = "",
                                language = "eng",
                            ),
                        ),
                ),
                directPlay(),
            )

        // An unaddressable track could be turned on and never off again.
        spec.tracks.shouldBeEmpty()
    }

    @Test
    fun `a subtitle is announced as WebVTT whatever the source stream was`() {
        // The cast profile declares `vtt` as the only external format, so the server converts a
        // subrip stream on the way out — but the local spec still names the *source's* codec.
        val spec =
            mapper.map(
                itemSpec(
                    subtitles =
                        listOf(
                            subtitleSpec(index = 4, uri = "https://server/s.vtt")
                                .copy(mimeType = MimeTypes.APPLICATION_SUBRIP),
                        ),
                ),
                directPlay(),
            )

        spec.tracks.single().mimeType shouldBe MimeTypes.TEXT_VTT
    }

    @Test
    fun `a direct-played mp4 is announced as mp4`() {
        val spec = mapper.map(itemSpec(), directPlay(container = "mp4"))

        spec.contentType shouldBe "video/mp4"
    }

    @Test
    fun `a direct-streamed webm is announced as webm`() {
        val spec =
            mapper.map(
                itemSpec(),
                PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_STREAM, container = "webm"),
            )

        spec.contentType shouldBe "video/webm"
    }

    @Test
    fun `a transcode is announced as HLS, which a receiver does not sniff`() {
        val spec = mapper.map(itemSpec(mimeType = MimeTypes.APPLICATION_M3U8), transcode())

        spec.contentType shouldBe MimeTypes.APPLICATION_M3U8
    }

    @Test
    fun `carries the runtime and the resume position the negotiation settled on`() {
        val spec = mapper.map(itemSpec(), directPlay(startPositionTicks = 12_000_000_000L))

        spec.durationMs shouldBe PlayerFixtures.RUN_TIME_TICKS / 10_000L
        spec.startPositionMs shouldBe 1_200_000L
        spec.streamType shouldBe CastStreamType.Buffered
    }

    @Test
    fun `a source with no runtime is a live one, which the receiver must not try to seek`() {
        val spec =
            mapper.map(
                itemSpec(),
                PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY).copy(runTimeTicks = 0L),
            )

        spec.streamType shouldBe CastStreamType.Live
    }

    @Test
    fun `passes the screen's metadata through untouched`() {
        val metadata = CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = "https://server/p.jpg")

        val spec = mapper.map(itemSpec(), directPlay(), metadata)

        spec.metadata shouldBe metadata
        spec.mediaId shouldContain PlayerFixtures.ITEM_ID.toString()
    }

    private fun itemSpec(
        uri: String = "https://server/Videos/x/stream?static=true",
        mimeType: String? = null,
        subtitles: List<SubtitleSpec> = emptyList(),
    ) = PlaybackMediaItemSpec(
        mediaId = PlayerFixtures.ITEM_ID.toString(),
        uri = uri,
        mimeType = mimeType,
        subtitles = subtitles,
    )

    private fun subtitleSpec(
        index: Int,
        uri: String,
    ) = SubtitleSpec(
        id = externalSubtitleTrackId(index),
        uri = uri,
        mimeType = MimeTypes.TEXT_VTT,
        label = "English",
        language = "eng",
    )

    private fun directPlay(
        container: String = "mp4",
        startPositionTicks: Long = 0L,
    ) = PlayerFixtures.remoteSource(
        playMethod = PlayMethod.DIRECT_PLAY,
        container = container,
        startPositionTicks = startPositionTicks,
    )

    private fun transcode() =
        PlayerFixtures.remoteSource(
            playMethod = PlayMethod.TRANSCODE,
            transcodingUrl = "/videos/x/master.m3u8",
        )

    private companion object {
        const val TOKEN = "tok3n"
        const val SUBTITLES = "https://server/Videos/x/Subtitles"
    }
}
