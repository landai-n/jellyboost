package dev.jellyboost.player.resolve

import androidx.media3.common.MimeTypes
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.ExternalAudio
import dev.jellyboost.player.model.ExternalSubtitle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ExoMediaSourceFactory].
 *
 * Each play method needs a completely different URL, and picking the wrong one fails in ways that
 * look like a decoder bug rather than a URL bug — a missing `static=true` quietly remuxes, and an
 * HLS playlist without its MIME type is parsed as a progressive stream.
 */
class ExoMediaSourceFactoryTest {
    private val urls =
        object : StreamUrlFactory {
            override fun directPlayUrl(
                itemId: UUID,
                mediaSourceId: String,
                playSessionId: String,
            ) = "https://server/Videos/$itemId/stream?static=true" +
                "&mediaSourceId=$mediaSourceId&playSessionId=$playSessionId"

            override fun directStreamUrl(
                itemId: UUID,
                container: String,
                mediaSourceId: String,
                playSessionId: String,
            ) = "https://server/Videos/$itemId/stream.$container?playSessionId=$playSessionId"

            override fun absoluteUrl(path: String) = "https://server$path"

            // Trickplay is the scrubber's business, not this factory's; present so the fake still
            // satisfies the interface.
            override fun trickplayTileUrl(
                itemId: UUID,
                width: Int,
                tileIndex: Int,
                mediaSourceId: String?,
            ) = "https://server/Videos/$itemId/Trickplay/$width/$tileIndex.jpg"
        }

    private val factory = ExoMediaSourceFactory(urls)

    @Test
    fun `direct play of a server file asks for the untouched bytes`() {
        val spec = factory.create(PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY))

        spec.shouldNotBeNull()
        spec.uri shouldBe
            "https://server/Videos/${PlayerFixtures.ITEM_ID}/stream?static=true" +
            "&mediaSourceId=${PlayerFixtures.ITEM_ID}&playSessionId=${PlayerFixtures.PLAY_SESSION_ID}"
        // Progressive: letting the extractor sniff the container is correct here.
        spec.mimeType.shouldBeNull()
        spec.mediaId shouldBe PlayerFixtures.ITEM_ID.toString()
    }

    @Test
    fun `direct play of an http source plays the playlist the server handed us`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.DIRECT_PLAY,
                    protocol = MediaProtocol.HTTP,
                    path = "https://elsewhere/live.m3u8",
                ),
            )

        spec.shouldNotBeNull()
        spec.uri shouldBe "https://elsewhere/live.m3u8"
        spec.mimeType shouldBe MimeTypes.APPLICATION_M3U8
    }

    @Test
    fun `direct play over http without a path is unplayable`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.DIRECT_PLAY,
                    protocol = MediaProtocol.HTTP,
                    path = null,
                ),
            )

        spec.shouldBeNull()
    }

    @Test
    fun `an unsupported protocol is refused rather than guessed at`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.DIRECT_PLAY,
                    protocol = MediaProtocol.RTSP,
                ),
            )

        spec.shouldBeNull()
    }

    @Test
    fun `direct stream requests the remuxed container`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_STREAM, container = "mkv"),
            )

        spec.shouldNotBeNull()
        spec.uri shouldBe
            "https://server/Videos/${PlayerFixtures.ITEM_ID}/stream.mkv" +
            "?playSessionId=${PlayerFixtures.PLAY_SESSION_ID}"
        spec.mimeType.shouldBeNull()
    }

    @Test
    fun `direct stream without a container is unplayable`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_STREAM, container = null),
            )

        spec.shouldBeNull()
    }

    @Test
    fun `a transcode is opened as HLS, with the MIME type spelled out`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.TRANSCODE,
                    transcodingUrl = "/videos/x/master.m3u8?api_key=abc",
                    transcodingSubProtocol = MediaStreamProtocol.HLS,
                ),
            )

        spec.shouldNotBeNull()
        spec.uri shouldBe "https://server/videos/x/master.m3u8?api_key=abc"
        // The URL's extension is not enough — the query string hides it from the sniffer.
        spec.mimeType shouldBe MimeTypes.APPLICATION_M3U8
    }

    @Test
    fun `a transcode offered over anything but HLS is refused`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.TRANSCODE,
                    transcodingUrl = "/videos/x/stream.mp4",
                    transcodingSubProtocol = MediaStreamProtocol.HTTP,
                ),
            )

        spec.shouldBeNull()
    }

    @Test
    fun `a transcode without a transcoding url is refused`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE, transcodingUrl = null),
            )

        spec.shouldBeNull()
    }

    @Test
    fun `external subtitles become side-loaded sources tagged with their Jellyfin index`() {
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.DIRECT_PLAY,
                    externalSubtitles =
                        listOf(
                            ExternalSubtitle(
                                index = 4,
                                url = "/Videos/1/Subtitles/4/Stream.srt",
                                mimeType = MimeTypes.APPLICATION_SUBRIP,
                                label = "English",
                                language = "eng",
                            ),
                        ),
                ),
            )

        spec.shouldNotBeNull()
        val subtitle = spec.subtitles.single()
        // The id is the bridge back to the Jellyfin stream index when a track is selected.
        subtitle.id shouldBe "external:4"
        subtitle.uri shouldBe "https://server/Videos/1/Subtitles/4/Stream.srt"
        subtitle.mimeType shouldBe MimeTypes.APPLICATION_SUBRIP
        subtitle.language shouldBe "eng"
        subtitle.label shouldBe "English"
    }

    @Test
    fun `a source with no external subtitles gets none`() {
        val spec = factory.create(PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY))

        spec.shouldNotBeNull()
        spec.subtitles.shouldBeEmpty()
    }

    // ---- M8, local sources ----------------------------------------------------------------------

    @Test
    fun `a downloaded item is opened straight off the file, with no URL construction`() {
        val spec = factory.create(PlayerFixtures.localSource())

        spec.shouldNotBeNull()
        spec.uri shouldBe PlayerFixtures.LOCAL_MEDIA_URI
        // Left unset: ExoPlayer sniffs a local container far more reliably than a guess from the
        // filename the download pipeline copied off the server.
        spec.mimeType.shouldBeNull()
        spec.mediaId shouldBe PlayerFixtures.ITEM_ID.toString()
    }

    @Test
    fun `a downloaded sidecar keeps its file URI instead of being prefixed with the server`() {
        val spec =
            factory.create(
                PlayerFixtures.localSource(
                    externalSubtitles =
                        listOf(
                            ExternalSubtitle(
                                index = 3,
                                url = "file:///downloads/Arrival/subtitle.3.eng.srt",
                                mimeType = MimeTypes.APPLICATION_SUBRIP,
                                label = "English",
                                language = "eng",
                            ),
                        ),
                ),
            )

        spec.shouldNotBeNull()
        val subtitle = spec.subtitles.single()
        // Running this through `absoluteUrl` would produce `https://serverfile:///…`.
        subtitle.uri shouldBe "file:///downloads/Arrival/subtitle.3.eng.srt"
        // The same track-id convention as online, so `TrackSelectionController` needs no branch.
        subtitle.id shouldBe "external:3"
        subtitle.mimeType shouldBe MimeTypes.APPLICATION_SUBRIP
    }

    // ---- audio sidecars (phase 2) ---------------------------------------------------------------

    @Test
    fun `downloaded audio sidecars reach the spec in the order they will be merged in`() {
        val spec =
            factory.create(
                PlayerFixtures.localSource(
                    externalAudio =
                        listOf(
                            ExternalAudio(index = 4, uri = "file:///downloads/Arrival/audio.4.fra.m4a"),
                            ExternalAudio(index = 5, uri = "file:///downloads/Arrival/audio.5.eng.m4a"),
                        ),
                ),
            )

        spec.shouldNotBeNull()
        // Element i becomes merge child i+1; the order is the whole of the mapping back to Jellyfin
        // stream indices, so nothing between here and `ExoPlayerHandle` may re-sort it.
        spec.audioSidecars.map { it.streamIndex } shouldBe listOf(4, 5)
        spec.audioSidecars.map { it.uri } shouldBe
            listOf("file:///downloads/Arrival/audio.4.fra.m4a", "file:///downloads/Arrival/audio.5.eng.m4a")
    }

    @Test
    fun `a streamed source never carries audio sidecars`() {
        // The server merges what it sends; there is nothing on this side to merge.
        val spec =
            factory.create(
                PlayerFixtures.remoteSource(
                    playMethod = PlayMethod.TRANSCODE,
                    transcodingUrl = "/videos/x/master.m3u8",
                    transcodingSubProtocol = MediaStreamProtocol.HLS,
                ),
            )

        spec.shouldNotBeNull()
        spec.audioSidecars.shouldBeEmpty()
    }

    @Test
    fun `a downloaded item with no extra languages gets no audio sidecars`() {
        val spec = factory.create(PlayerFixtures.localSource())

        spec.shouldNotBeNull()
        spec.audioSidecars.shouldBeEmpty()
    }
}
