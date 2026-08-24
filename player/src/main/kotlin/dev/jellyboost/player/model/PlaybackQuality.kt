package dev.jellyboost.player.model

/**
 * [AUTO]'s `null` cap is not what reaches the server: `PlaybackInfoResolver` fills it in from
 * `AutoBitrateDetector`'s measured throughput. `null` survives only when measurement failed with no
 * prior, leaving the device profile's 120 Mbps ceiling — the one case [lowerThan] sees a `null` cap.
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
        fun forBitrate(bitrate: Int?): PlaybackQuality =
            entries.firstOrNull { it.maxStreamingBitrate == bitrate } ?: AUTO

        fun lowerThan(bitrate: Int?): PlaybackQuality? {
            val capped = bitrate ?: HIGH.maxStreamingBitrate
            return entries
                .filter { it.maxStreamingBitrate != null && it.maxStreamingBitrate < (capped ?: 0) }
                .maxByOrNull { requireNotNull(it.maxStreamingBitrate) }
        }
    }
}

/** 20 Mbps — above a 1080p H.264 remux, so most files still direct-play. */
private const val BITRATE_HIGH = 20_000_000

/** 8 Mbps — a 1080p transcode. */
private const val BITRATE_MEDIUM = 8_000_000

/** 3 Mbps — a 720p transcode. */
private const val BITRATE_LOW = 3_000_000

private const val BITRATE_LOWEST = 720_000
