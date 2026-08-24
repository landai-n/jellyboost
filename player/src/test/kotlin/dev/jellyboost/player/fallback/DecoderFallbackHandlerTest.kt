package dev.jellyboost.player.fallback

import androidx.media3.common.PlaybackException
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DecoderFallbackHandler].
 *
 * This is the mitigation for the plan's risk #5 (OEM decoder quirks), and the failure mode it
 * guards against — retrying forever, or giving up on the first hiccup — is only visible under
 * exactly the sequences exercised here.
 */
class DecoderFallbackHandlerTest {
    private val handler = DecoderFallbackHandler()

    @Test
    fun `a decoder failure forces the server to transcode`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                source = PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY),
                positionTicks = 600L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.ForceTranscode>()
        decision.positionTicks shouldBe 600L
    }

    @Test
    fun `a failed decoder initialisation is treated the same way`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                source = PlayerFixtures.remoteSource(),
                positionTicks = 0L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.ForceTranscode>()
    }

    @Test
    fun `an audio track failure counts as a decoder failure`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                source = PlayerFixtures.remoteSource(),
                positionTicks = 0L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.ForceTranscode>()
    }

    @Test
    fun `forcing a transcode is attempted once, then abandoned`() {
        val source = PlayerFixtures.remoteSource()
        handler.onPlayerError(PlaybackException.ERROR_CODE_DECODING_FAILED, source, 0L)

        val second = handler.onPlayerError(PlaybackException.ERROR_CODE_DECODING_FAILED, source, 0L)

        // Without this the same file would loop through the same failure forever.
        second shouldBe FallbackDecision.GiveUp
    }

    @Test
    fun `a stalled transcode retries once at a lower bitrate`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                source =
                    PlayerFixtures.remoteSource(
                        playMethod = PlayMethod.TRANSCODE,
                        maxStreamingBitrate = 20_000_000,
                    ),
                positionTicks = 1_200L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.LowerBitrate>()
        decision.maxStreamingBitrate shouldBe 8_000_000
        decision.positionTicks shouldBe 1_200L
    }

    @Test
    fun `a malformed container while transcoding also drops the bitrate`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                source =
                    PlayerFixtures.remoteSource(
                        playMethod = PlayMethod.TRANSCODE,
                        maxStreamingBitrate = 8_000_000,
                    ),
                positionTicks = 0L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.LowerBitrate>()
        decision.maxStreamingBitrate shouldBe 3_000_000
    }

    @Test
    fun `a measured cap that is not a rung drops onto the next rung below it`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                source =
                    PlayerFixtures.remoteSource(
                        playMethod = PlayMethod.TRANSCODE,
                        // What Auto's measurement produces: a number off the ladder entirely.
                        maxStreamingBitrate = 5_000_000,
                        autoBitrate = true,
                    ),
                positionTicks = 0L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.LowerBitrate>()
        decision.maxStreamingBitrate shouldBe 3_000_000
    }

    @Test
    fun `lowering the bitrate is attempted once, then abandoned`() {
        val source =
            PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = 20_000_000)
        handler.onPlayerError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, source, 0L)

        val second = handler.onPlayerError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, source, 0L)

        second shouldBe FallbackDecision.GiveUp
    }

    @Test
    fun `there is nothing below the lowest bitrate to retry at`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                source =
                    PlayerFixtures.remoteSource(
                        playMethod = PlayMethod.TRANSCODE,
                        maxStreamingBitrate = 720_000,
                    ),
                positionTicks = 0L,
            )

        decision shouldBe FallbackDecision.GiveUp
    }

    @Test
    fun `a source error while direct playing forces a transcode instead`() {
        // Lowering the bitrate of a direct play changes nothing — the server is sending the file
        // untouched — so the only useful move is to stop asking it to.
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                source = PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY),
                positionTicks = 300L,
            )

        decision.shouldBeInstanceOf<FallbackDecision.ForceTranscode>()
        decision.positionTicks shouldBe 300L
    }

    @Test
    fun `an unclassified failure is not worth retrying`() {
        val decision =
            handler.onPlayerError(
                errorCode = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                source = PlayerFixtures.remoteSource(),
                positionTicks = 0L,
            )

        decision shouldBe FallbackDecision.GiveUp
    }

    @Test
    fun `playback getting going again earns a fresh set of retries`() {
        val source = PlayerFixtures.remoteSource()
        handler.onPlayerError(PlaybackException.ERROR_CODE_DECODING_FAILED, source, 0L)

        handler.onPlaybackStarted()

        // An unrelated failure an hour later must not inherit the first one's exhausted budget.
        handler
            .onPlayerError(PlaybackException.ERROR_CODE_DECODING_FAILED, source, 0L)
            .shouldBeInstanceOf<FallbackDecision.ForceTranscode>()
    }

    @Test
    fun `classifies error codes by Media3's own grouping`() {
        errorKindOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) shouldBe PlaybackErrorKind.RENDERER
        errorKindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) shouldBe PlaybackErrorKind.SOURCE
        errorKindOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) shouldBe PlaybackErrorKind.SOURCE
        errorKindOf(PlaybackException.ERROR_CODE_UNSPECIFIED) shouldBe PlaybackErrorKind.OTHER
    }
}
