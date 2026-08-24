package dev.jellyboost.core.common.model

import java.time.Instant

/**
 * Per-user playback state for a single item.
 *
 * Produced identically by the online and the offline repository so that resume behaviour is
 * byte-identical in both modes.
 */
data class UserData(
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val playbackPositionTicks: Long = 0L,
    val playedPercentage: Double? = null,
    val playCount: Int = 0,
    val lastPlayedDate: Instant? = null,
) {
    /** `true` when playback was started but not finished — the "Continue watching" condition. */
    val isResumable: Boolean get() = playbackPositionTicks > 0L && !played

    /**
     * Progress in `0f..1f` for the progress bar drawn across a card, or `null` when the item has
     * never been started. Prefers the server-supplied percentage and falls back to
     * position/runtime when the caller knows [runTimeTicks].
     */
    fun progress(runTimeTicks: Long?): Float? {
        playedPercentage?.let { return (it / PERCENT).toFloat().coerceIn(0f, 1f) }
        if (playbackPositionTicks <= 0L || runTimeTicks == null || runTimeTicks <= 0L) return null
        return (playbackPositionTicks.toDouble() / runTimeTicks.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val PERCENT = 100.0
    }
}
