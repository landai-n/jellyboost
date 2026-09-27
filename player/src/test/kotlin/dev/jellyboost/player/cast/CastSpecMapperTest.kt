package dev.jellyboost.player.cast

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.SubtitleSpec
import dev.jellyboost.player.model.externalSubtitleTrackId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Everything a cast session can get wrong *quietly* is decided here. A media URL without its token
 * is a receiver that shows nothing and reports nothing back; a subtitle URL without one is a
 * subtitle that never appears; a track id that is not the Jellyfin stream index is a picker entry
 * that turns the wrong language on. None of the three is visible on this device, which is why they
 * are pinned in plain data rather than left to the on-device assembly.
 */
class CastSpecMapperTest {
    // Only [StreamUrlFactory.withApiKey] matters here; the rest is never called.
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
        // The server returns `TranscodingUrl` and every subtitle `DeliveryUrl` with `ApiKey`
        // already on them; appending a second one would make the query ambiguous.
        val signed = "https://server/videos/x/master.m3u8?PlaySessionId=s&ApiKey=$TOKEN"

        val spec = mapper.map(itemSpec(uri = signed, mimeType = MimeTypes.APPLICATION_M3U8), transcode())

        spec.contentId shouldBe signed
    }

    @Test
    fun `the server's no-stream-copy flag reaches the receiver on the transcode URL`() {
        // The resolver's `allowVideoStreamCopy = false` travels as a query parameter the *server*
        // appends to `TranscodingUrl` (10.11: MediaInfoHelper). A stream-copied cast transcode has
        // segments that stop matching its playlist once ffmpeg restarts mid-file, so the mapper
        // must hand the URL over with it intact.
        val url =
            "https://server/videos/x/master.m3u8?PlaySessionId=s&VideoCodec=h264&MaxWidth=1920" +
                "&allowVideoStreamCopy=false"

        val spec = mapper.map(itemSpec(uri = url, mimeType = MimeTypes.APPLICATION_M3U8), transcode())

        spec.contentId shouldContain "&allowVideoStreamCopy=false"
        spec.contentId shouldBe "$url&ApiKey=$TOKEN"
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
    fun `the screen's words reach the receiver as they are`() {
        val metadata = CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = null)

        val spec = mapper.map(itemSpec(), directPlay(), metadata)

        spec.metadata shouldBe metadata
        spec.mediaId shouldContain PlayerFixtures.ITEM_ID.toString()
    }

    @Test
    fun `the poster is not signed — the token goes only where the fetch needs it`() {
        // Everything handed to the receiver is republished in its MediaStatus for any sender on
        // the network to read — image endpoints answer without credentials, so no token belongs here.
        val metadata = CastMetadata(title = "Arrival", subtitle = "2016", posterUrl = "https://server/p.jpg")

        val spec = mapper.map(itemSpec(), directPlay(), metadata)

        spec.metadata shouldBe metadata
    }

    @Test
    fun `an item without a poster stays without one`() {
        val none = mapper.map(itemSpec(), directPlay(), CastMetadata(title = "Arrival"))

        // Not the empty string, and not a signed URL with no path.
        none.metadata.posterUrl shouldBe null
    }

    @Test
    fun `nothing is announced when the screen never got round to naming the film`() {
        // A receiver reached before the item fetch settles must still play rather than fail over a caption.
        val spec = mapper.map(itemSpec(), directPlay())

        spec.metadata shouldBe CastMetadata()
    }

    // ---- opening it on the receiver ----------------------------------------------------------------

    @Test
    fun `a cast open sets playWhenReady before the media item, so the load carries the autoplay`() {
        // `RemoteCastPlayer.setMediaItems` loads at once with `setAutoplay(getPlayWhenReady())`:
        // after a receiver left paused, a flag set afterwards loaded the film paused.
        val player = mockk<Player>(relaxed = true)
        val item = MediaItem.Builder().setMediaId("film").build()

        player.openForCast(item, startPositionMs = 42_000L, playWhenReady = true)

        verifyOrder {
            player.playWhenReady = true
            player.setMediaItem(item, 42_000L)
            player.prepare()
        }
    }

    @Test
    fun `a negative start position opens from the beginning`() {
        val player = mockk<Player>(relaxed = true)
        val item = MediaItem.Builder().setMediaId("film").build()

        player.openForCast(item, startPositionMs = -1L, playWhenReady = false)

        verifyOrder {
            player.playWhenReady = false
            player.setMediaItem(item, 0L)
        }
    }

    @Test
    fun `the open's playWhenReady travels to the converter as the queue item's autoplay`() {
        // `toUri` is an Android stub off a device; the URI itself is not what is under test. The
        // converter's own `MediaQueueItem` needs Play services, so the pin stops at what it reads.
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk(relaxed = true)
            val spec = mapper.map(itemSpec(uri = "https://server/Videos/x/stream"), directPlay())

            spec.autoplay shouldBe true
            val paused = spec.copy(autoplay = false).toMediaItem()
            val playing = spec.copy(autoplay = true).toMediaItem()

            paused.castSpec()?.autoplay shouldBe false
            playing.castSpec()?.autoplay shouldBe true
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    /**
     * End to end through the handle's own load ([loadOnReceiver], what `CastPlayerHandle.prepare` calls):
     * the `MediaItem` the player is given carries the spec the converter reads, with the open's flag —
     * never the mapper's default of `true`.
     */
    @Test
    fun `a load opened paused hands the player an item whose autoplay is false`() {
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val items = mutableListOf<MediaItem>()
            every { player.setMediaItem(capture(items), any<Long>()) } returns Unit
            val mapped = mapper.map(itemSpec(uri = "https://server/Videos/x/stream"), directPlay())

            val sent = player.loadOnReceiver(mapped, startPositionMs = 42_000L, playWhenReady = false)

            mapped.autoplay shouldBe true
            sent.autoplay shouldBe false
            items.single().castSpec()?.autoplay shouldBe false
            verifyOrder {
                player.playWhenReady = false
                player.setMediaItem(items.single(), 42_000L)
                player.prepare()
            }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun `a load opened playing hands the player an item whose autoplay is true`() {
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val items = mutableListOf<MediaItem>()
            every { player.setMediaItem(capture(items), any<Long>()) } returns Unit
            val mapped = mapper.map(itemSpec(uri = "https://server/Videos/x/stream"), directPlay())

            player.loadOnReceiver(mapped.copy(autoplay = false), startPositionMs = 0L, playWhenReady = true)

            items.single().castSpec()?.autoplay shouldBe true
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    // ---- a start late in an HLS segment ------------------------------------------------------

    @Test
    fun `a re-encoded HLS transcode with a known frame rate carries the server's segment length`() {
        mapper.map(hlsItem(), reEncode(frameRate = 24f)).hlsSegmentMs shouldBe 3_000.0
        requireNotNull(mapper.map(hlsItem(), reEncode(frameRate = 23.976025f)).hlsSegmentMs) shouldBe
            (3_003.0 plusOrMinus 0.01)
        // The server reads its parameters case-insensitively, and so does the mapper.
        mapper
            .map(hlsItem(), reEncode(frameRate = 24f, url = "/videos/x/master.m3u8?AllowVideoStreamCopy=False"))
            .hlsSegmentMs shouldBe 3_000.0
    }

    @Test
    fun `no segment grid for direct play, an unknown frame rate, a stream copy or a URL setting its own frame rate`() {
        mapper.map(itemSpec(), directPlay().copy(videoFrameRate = 24f)).hlsSegmentMs shouldBe null
        mapper.map(hlsItem(), reEncode(frameRate = null)).hlsSegmentMs shouldBe null
        // Without the server's own allowVideoStreamCopy=false the video may be copied, and a copy is
        // laid out on the file's keyframes, not on a fixed grid.
        mapper
            .map(hlsItem(), reEncode(frameRate = 24f, url = "/videos/x/master.m3u8?VideoCodec=h264"))
            .hlsSegmentMs shouldBe null
        mapper
            .map(hlsItem(), reEncode(frameRate = 24f, url = "$TRANSCODE_URL&MaxFramerate=23.976"))
            .hlsSegmentMs shouldBe null
        mapper
            .map(hlsItem(), reEncode(frameRate = 24f).copy(runTimeTicks = 0L))
            .hlsSegmentMs shouldBe null
    }

    @Test
    fun `a segment length the transcode URL names replaces the nominal 3 s`() {
        mapper.map(hlsItem(), reEncode(frameRate = 24f, url = "$TRANSCODE_URL&SegmentLength=6")).hlsSegmentMs shouldBe
            6_000.0
        mapper.map(hlsItem(), reEncode(frameRate = 24f, url = "$TRANSCODE_URL&SegmentLength=x")).hlsSegmentMs shouldBe
            null
    }

    /**
     * The device-measured stall: a 24 fps transcode loaded 2.881 s into segment 1018 sat in BUFFERING
     * forever. The load must carry a start 1 s into that segment — as the player's position and as the
     * queue item's start time the converter reads.
     */
    @Test
    fun `a transcode load late in a segment carries the snapped start`() {
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val items = mutableListOf<MediaItem>()
            every { player.setMediaItem(capture(items), any<Long>()) } returns Unit
            val mapped =
                mapper.map(hlsItem(), reEncode(frameRate = 24f, startPositionTicks = 3_056_881L * TICKS_PER_MS))

            val sent = player.loadOnReceiver(mapped, startPositionMs = 3_056_881L, playWhenReady = true)

            sent.startPositionMs shouldBe 3_055_000L
            items.single().castSpec()?.startPositionMs shouldBe 3_055_000L
            verifyOrder {
                player.playWhenReady = true
                player.setMediaItem(items.single(), 3_055_000L)
                player.prepare()
            }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun `a transcode load early in a segment, and a direct play load anywhere, start where they were asked to`() {
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns mockk(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val items = mutableListOf<MediaItem>()
            every { player.setMediaItem(capture(items), any<Long>()) } returns Unit
            val early =
                mapper.map(hlsItem(), reEncode(frameRate = 24f, startPositionTicks = 3_054_648L * TICKS_PER_MS))
            val direct =
                mapper.map(
                    itemSpec(),
                    directPlay(startPositionTicks = 3_056_881L * TICKS_PER_MS).copy(videoFrameRate = 24f),
                )

            player.loadOnReceiver(early, startPositionMs = 3_054_648L, playWhenReady = true)
            player.loadOnReceiver(direct, startPositionMs = 3_056_881L, playWhenReady = true)

            items.map { it.castSpec()?.startPositionMs } shouldBe listOf(3_054_648L, 3_056_881L)
            verifyOrder {
                player.setMediaItem(items[0], 3_054_648L)
                player.setMediaItem(items[1], 3_056_881L)
            }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    /** A cast transcode as the server hands it back: re-encoding, the flag echoed into its URL. */
    private fun reEncode(
        frameRate: Float?,
        url: String = TRANSCODE_URL,
        startPositionTicks: Long = 0L,
    ) = transcode(frameRate = frameRate, url = url, startPositionTicks = startPositionTicks)

    private fun hlsItem() = itemSpec(uri = "https://server$TRANSCODE_URL", mimeType = MimeTypes.APPLICATION_M3U8)

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

    private fun transcode(
        frameRate: Float? = null,
        url: String = "/videos/x/master.m3u8",
        startPositionTicks: Long = 0L,
    ) = PlayerFixtures.remoteSource(
        playMethod = PlayMethod.TRANSCODE,
        transcodingUrl = url,
        videoFrameRate = frameRate,
        startPositionTicks = startPositionTicks,
    )

    private companion object {
        const val TOKEN = "tok3n"
        const val TICKS_PER_MS = 10_000L

        /** What the server hands a cast negotiation: it echoes the re-encode flag into the URL. */
        const val TRANSCODE_URL = "/videos/x/master.m3u8?VideoCodec=h264&allowVideoStreamCopy=false"
        const val SUBTITLES = "https://server/Videos/x/Subtitles"
    }
}
