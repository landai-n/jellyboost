package dev.jellyboost.core.common

/**
 * Human-readable time remaining — `"45 s"`, `"3 min"`, `"1 h 20 min"`.
 *
 * Rounds **up** rather than to the nearest unit: an ETA is a promise about how much longer something takes,
 * and 61 seconds shown as "1 min" would land short of it. Android-free, so every feature module can call it.
 */
fun formatDurationSeconds(seconds: Long): String {
    val clamped = seconds.coerceAtLeast(0L)
    return when {
        clamped < SECONDS_PER_MINUTE -> "$clamped s"

        clamped < SECONDS_PER_HOUR -> "${ceilDiv(clamped, SECONDS_PER_MINUTE)} min"

        else -> {
            val hours = clamped / SECONDS_PER_HOUR
            val remainderSeconds = clamped % SECONDS_PER_HOUR
            val minutes = ceilDiv(remainderSeconds, SECONDS_PER_MINUTE)

            // A remainder of 59.02 min ceils to 60 — a sixtieth minute does not exist, it is the next hour.
            if (minutes == MINUTES_PER_HOUR) {
                "${hours + 1} h"
            } else if (minutes == 0L) {
                "$hours h"
            } else {
                "$hours h $minutes min"
            }
        }
    }
}

private fun ceilDiv(
    numerator: Long,
    denominator: Long,
): Long = (numerator + denominator - 1) / denominator

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val MINUTES_PER_HOUR = 60L
