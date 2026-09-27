package dev.jellyboost.player.cast

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where a **transcoded HLS** cast load may start. Pure; see `docs/features/chromecast.md`,
 * "A start late in a segment".
 *
 * A Default Media Receiver loading a Jellyfin HLS transcode at a position in the last ~0.5 s of a
 * segment sits in `BUFFERING` forever (device-measured: offsets 2.590 s and 2.881 s into 3 s
 * segments stalled; 0.648 s–1.88 s all played). The server restarts ffmpeg at the segment's start
 * with `-ss`, and the job the receiver starts first is measured ~83 ms apart from the restarted one,
 * which likely pushes a late target past the first restarted segment's audio. Starting no more than
 * [MAX_OFFSET_MS] into the segment stays inside the measured-safe range, at the cost of replaying at
 * most ~2 s the viewer has already seen.
 */
internal object HlsSegmentSnap {
    /** The server's segment length for a re-encoded (never stream-copied) HLS transcode. */
    const val NOMINAL_SEGMENT_SECONDS = 3

    /** The latest point in a segment a load is allowed to start at. */
    const val MAX_OFFSET_MS = 1_000L

    /**
     * Guards the ceiling against float noise: `3 × 24f` must stay 72 frames, not become 73. Far below
     * the smallest real fraction a frame rate leaves (23.976 × 3 = 71.928).
     */
    private const val FRAME_EPSILON = 1e-3

    private const val MILLIS_PER_SECOND = 1_000.0

    /**
     * The server's actual segment length for a transcode at [frameRate]: a whole number of frames,
     * `ceil(nominal × fps) / fps` — 3.000 s at 24 fps, 3.003 s (72 frames) at 23.976 fps. Verified
     * against the server's own restarts: a 24 fps film restarts at `-ss N × 3.000` with `-g 72`, a
     * 23.976 fps one at `-ss 00:03:09.189` / `00:22:07.326` (63 and 442 × 3.003) with `-g 72`.
     *
     * `null` when the frame rate is unknown or not a positive finite number: no length, no snap.
     */
    fun segmentMs(
        frameRate: Float?,
        nominalSeconds: Int = NOMINAL_SEGMENT_SECONDS,
    ): Double? {
        val fps = frameRate?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (nominalSeconds <= 0) return null
        val frames = ceil(nominalSeconds * fps - FRAME_EPSILON)
        return frames / fps * MILLIS_PER_SECOND
    }

    /**
     * [positionMs], or — when it lies more than [MAX_OFFSET_MS] into its segment — that segment's
     * start plus [MAX_OFFSET_MS]. Never later than [positionMs], never more than one segment earlier,
     * and never 0 unless [positionMs] was (the zero rule reads a zero as a torn-down receiver). A
     * `null` [segmentMs] (direct play, unknown frame rate) or a negative position is returned as is.
     */
    fun snapStartMs(
        positionMs: Long,
        segmentMs: Double?,
    ): Long {
        val segment = segmentMs?.takeIf { it.isFinite() && it > MAX_OFFSET_MS } ?: return positionMs
        if (positionMs <= 0L) return positionMs
        val segmentStart = floor(positionMs / segment) * segment
        val latest = floor(segmentStart + MAX_OFFSET_MS).toLong()
        return minOf(positionMs, latest)
    }
}
