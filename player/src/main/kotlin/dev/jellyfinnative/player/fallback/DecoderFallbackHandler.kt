package dev.jellyfinnative.player.fallback

import androidx.media3.common.PlaybackException
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import timber.log.Timber
import javax.inject.Inject

/**
 * Decides what to do when playback dies mid-stream.
 *
 * This is the mitigation the plan names for risk #5, OEM decoder quirks: a device profile is built
 * from what `MediaCodecList` *claims*, and some decoders accept a format at initialisation and
 * then fail on the first frame. Rather than showing the user an error, we re-negotiate with the
 * server under stricter terms (docs/PLAN.md, "Playback pipeline" → DecoderFallbackHandler):
 *
 * - a **renderer/decoder** failure means this device cannot play the file as delivered, so we
 *   forbid direct play *and* direct stream, forcing a transcode;
 * - a **source** failure while already transcoding is usually the server or the network failing to
 *   keep up, so we retry once at a lower bitrate.
 *
 * Stateful on purpose: without an attempt counter a failing item retries forever.
 */
class DecoderFallbackHandler
    @Inject
    constructor() {
        private var forcedTranscode = false
        private var loweredBitrate = false

        /** Called whenever playback gets going again, so a later, unrelated failure retries afresh. */
        fun onPlaybackStarted() {
            forcedTranscode = false
            loweredBitrate = false
        }

        /**
         * @param errorCode the failing player's `PlaybackException.errorCode`. Taken as a plain
         *   `Int` rather than the exception itself because constructing a `PlaybackException`
         *   reaches into `SystemClock`, which would drag this otherwise pure class onto a device
         *   to be tested.
         * @param source what was playing when it broke.
         * @param positionTicks where to resume the retry from.
         * @return the recovery to attempt, or [FallbackDecision.GiveUp] when there is none left.
         */
        fun onPlayerError(
            errorCode: Int,
            source: RemotePlaybackMediaSource,
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
         * Only meaningful once the server is already transcoding: a source error on a direct play
         * is a network or file problem that a lower bitrate would not fix, so that case falls
         * through to forcing a transcode instead.
         */
        private fun lowerBitrate(
            source: RemotePlaybackMediaSource,
            positionTicks: Long,
        ): FallbackDecision {
            if (source.playMethod != PlayMethod.TRANSCODE) return forceTranscode(positionTicks)
            if (loweredBitrate) return FallbackDecision.GiveUp

            val lower = PlaybackQuality.lowerThan(source.maxStreamingBitrate) ?: return FallbackDecision.GiveUp
            loweredBitrate = true
            Timber.i("Transcode stalled; retrying at %d bps", lower.maxStreamingBitrate)
            return FallbackDecision.LowerBitrate(
                maxStreamingBitrate = requireNotNull(lower.maxStreamingBitrate),
                positionTicks = positionTicks,
            )
        }
    }

/** What the player should try next after a failure. */
sealed interface FallbackDecision {
    /** Re-resolve with direct play and direct stream forbidden, resuming at [positionTicks]. */
    data class ForceTranscode(
        val positionTicks: Long,
    ) : FallbackDecision

    /** Re-resolve with a lower bitrate cap, resuming at [positionTicks]. */
    data class LowerBitrate(
        val maxStreamingBitrate: Int,
        val positionTicks: Long,
    ) : FallbackDecision

    /** Nothing left to try — show the error. */
    data object GiveUp : FallbackDecision
}

/** The three classes of failure the fallback ladder distinguishes. */
enum class PlaybackErrorKind {
    /** The device's decoder rejected or choked on the stream. */
    RENDERER,

    /** The bytes did not arrive, or did not parse. */
    SOURCE,

    /** Anything else — a bug on our side, or an unrecoverable player state. */
    OTHER,
}

/**
 * Classifies an ExoPlayer error code.
 *
 * Matched against Media3's own numeric groupings rather than against individual codes, so that a
 * code added in a future Media3 release still lands in the right bucket.
 */
fun errorKindOf(errorCode: Int): PlaybackErrorKind =
    when (errorCode) {
        in DECODING_ERRORS, in AUDIO_TRACK_ERRORS -> PlaybackErrorKind.RENDERER
        in IO_ERRORS, in PARSING_ERRORS -> PlaybackErrorKind.SOURCE
        else -> PlaybackErrorKind.OTHER
    }

/** 4xxx — the device's decoder could not be started, or choked on the stream. */
private val DECODING_ERRORS =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

/** 5xxx — the audio output could not be opened or written to. Also a device-side failure. */
private val AUDIO_TRACK_ERRORS =
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED

/** 2xxx — the bytes did not arrive. */
private val IO_ERRORS =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

/** 3xxx — the bytes arrived but did not parse. */
private val PARSING_ERRORS =
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
