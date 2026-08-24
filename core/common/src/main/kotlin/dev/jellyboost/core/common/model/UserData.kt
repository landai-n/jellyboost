package dev.jellyboost.core.common.model

import java.time.Instant

/** Produced identically by the online and offline repositories, so resume behaviour matches in both modes. */
data class UserData(
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val playbackPositionTicks: Long = 0L,
    val playedPercentage: Double? = null,
    val playCount: Int = 0,
    val lastPlayedDate: Instant? = null,
) {
    val isResumable: Boolean get() = playbackPositionTicks > 0L && !played

    /** Prefers the server-supplied percentage, falling back to position/runtime when [runTimeTicks] is known. */
    fun progress(runTimeTicks: Long?): Float? {
        playedPercentage?.let { return (it / PERCENT).toFloat().coerceIn(0f, 1f) }
        if (playbackPositionTicks <= 0L || runTimeTicks == null || runTimeTicks <= 0L) return null
        return (playbackPositionTicks.toDouble() / runTimeTicks.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val PERCENT = 100.0
    }
}
