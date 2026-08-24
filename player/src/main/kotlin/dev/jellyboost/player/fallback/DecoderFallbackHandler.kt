package dev.jellyboost.player.fallback

import androidx.media3.common.PlaybackException
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import timber.log.Timber
import javax.inject.Inject

/**
 * A device profile is built from what `MediaCodecList` *claims*, and some decoders accept a format at
 * initialisation and then fail on the first frame; the recovery is to re-negotiate under stricter terms.
 *
 * Stateful on purpose: without an attempt counter a failing item retries forever.
 */
internal class DecoderFallbackHandler
    @Inject
    constructor() {
        private var forcedTranscode = false
        private var loweredBitrate = false

        /** Must be called whenever playback gets going again, or a later unrelated failure has no attempts left. */
        fun onPlaybackStarted() {
            forcedTranscode = false
            loweredBitrate = false
        }

        /**
         * @param errorCode a plain `Int`, not the `PlaybackException`: constructing one reaches into `SystemClock`
         *   and would make this class testable only on a device.
         */
        fun onPlayerError(
            errorCode: Int,
            source: PlaybackMediaSource,
            positionTicks: Long,
        ): FallbackDecision =
            when (errorKindOf(errorCode)) {
                PlaybackErrorKind.RENDERER -> forceTranscode(positionTicks)
                PlaybackErrorKind.SOURCE -> lowerBitrate(source, positionTicks)
                PlaybackErrorKind.OTHER -> FallbackDecision.GiveUp
            }

        private fun forceTranscode(positionTicks: Long): FallbackDecision =
            when {
                forcedTranscode -> FallbackDecision.GiveUp
                else -> {
                    forcedTranscode = true
                    Timber.i("Decoder failed; re-resolving with direct play and direct stream disabled")
                    FallbackDecision.ForceTranscode(positionTicks)
                }
            }

        /**
         * Only meaningful once the server is already transcoding; anything else — direct play, a local file —
         * falls through to forcing a transcode, since a lower bitrate cannot fix a network or file problem.
         */
        @Suppress(
            // Each exit is a rung of the bitrate ladder.
            "ReturnCount",
        )
        private fun lowerBitrate(
            source: PlaybackMediaSource,
            positionTicks: Long,
        ): FallbackDecision {
            val remote = source as? RemotePlaybackMediaSource ?: return forceTranscode(positionTicks)
            if (remote.playMethod != PlayMethod.TRANSCODE) return forceTranscode(positionTicks)
            if (loweredBitrate) return FallbackDecision.GiveUp

            val lower = PlaybackQuality.lowerThan(remote.maxStreamingBitrate) ?: return FallbackDecision.GiveUp
            loweredBitrate = true
            Timber.i("Transcode stalled; retrying at %d bps", lower.maxStreamingBitrate)
            return FallbackDecision.LowerBitrate(
                maxStreamingBitrate = requireNotNull(lower.maxStreamingBitrate),
                positionTicks = positionTicks,
            )
        }
    }

internal sealed interface FallbackDecision {
    /** Re-resolve with direct play *and* direct stream forbidden. */
    data class ForceTranscode(
        val positionTicks: Long,
    ) : FallbackDecision

    data class LowerBitrate(
        val maxStreamingBitrate: Int,
        val positionTicks: Long,
    ) : FallbackDecision

    data object GiveUp : FallbackDecision
}

internal enum class PlaybackErrorKind {
    RENDERER,
    SOURCE,
    OTHER,
}

/** Matched against Media3's numeric groupings, not individual codes, so codes added in a later release still bucket. */
internal fun errorKindOf(errorCode: Int): PlaybackErrorKind =
    when (errorCode) {
        in DECODING_ERRORS, in AUDIO_TRACK_ERRORS -> PlaybackErrorKind.RENDERER
        in IO_ERRORS, in PARSING_ERRORS -> PlaybackErrorKind.SOURCE
        else -> PlaybackErrorKind.OTHER
    }

/** 4xxx — device-side: the decoder. */
private val DECODING_ERRORS =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

/** 5xxx — device-side: the audio output. */
private val AUDIO_TRACK_ERRORS =
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED

/** 2xxx — the bytes did not arrive. */
private val IO_ERRORS =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

/** 3xxx — the bytes arrived but did not parse. */
private val PARSING_ERRORS =
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
