package dev.jellyboost.player.model

/**
 * The choices in the player's quality picker.
 *
 * A cap is sent to the server as `maxStreamingBitrate`; anything above it forces the server to
 * transcode, which is exactly how the milestone's "forced transcode" verification is performed
 * from the UI (docs/PLAN.md, M5 DoD). [AUTO] sends no cap at all and lets the device profile's
 * 120 Mbps ceiling apply.
 */
internal enum class PlaybackQuality(
    val maxStreamingBitrate: Int?,
) {
    AUTO(null),
    HIGH(BITRATE_HIGH),
    MEDIUM(BITRATE_MEDIUM),
    LOW(BITRATE_LOW),
    LOWEST(BITRATE_LOWEST),
    ;

    companion object {
        /** The picker entry matching [bitrate], falling back to [AUTO]. */
        fun forBitrate(bitrate: Int?): PlaybackQuality =
            entries.firstOrNull { it.maxStreamingBitrate == bitrate } ?: AUTO

        /** The next step down from [bitrate], used by the source-error retry. */
        fun lowerThan(bitrate: Int?): PlaybackQuality? {
            val capped = bitrate ?: HIGH.maxStreamingBitrate
            return entries
                .filter { it.maxStreamingBitrate != null && it.maxStreamingBitrate < (capped ?: 0) }
                .maxByOrNull { requireNotNull(it.maxStreamingBitrate) }
        }
    }
}

/** 20 Mbps — comfortably above a 1080p H.264 remux, so most files still direct-play. */
private const val BITRATE_HIGH = 20_000_000

/** 8 Mbps — a 1080p transcode. */
private const val BITRATE_MEDIUM = 8_000_000

/** 3 Mbps — a 720p transcode; low enough to force one on almost any library file. */
private const val BITRATE_LOW = 3_000_000

/** 720 kbps — the "make it work on this connection" setting. */
private const val BITRATE_LOWEST = 720_000
