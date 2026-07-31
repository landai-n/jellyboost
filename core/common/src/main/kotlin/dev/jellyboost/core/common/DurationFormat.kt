package dev.jellyboost.core.common

/**
 * Human-readable time remaining — `"45 s"`, `"3 min"`, `"1 h 20 min"` — for an ETA the user is
 * meant to glance at, not a stopwatch.
 *
 * Rounds up rather than to the nearest unit: an ETA is a promise about how much longer something
 * takes, and a download that still needs 61 more seconds reading "1 min" would land short of that
 * promise the moment it is shown. Ceiling division keeps the number always at least as large as the
 * real remaining time, the same asymmetry a user expects from any "time remaining" figure.
 *
 * Lives in `:core:common` next to [formatBytes] for the same reason ([formatBytes]'s KDoc,
 * ARCH-11): Android-free so every feature module can call it and unit-test it without a `Context`.
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

            // A remainder of, say, 59.02 min ceils to 60 — a sixtieth minute does not exist, it is
            // the next hour instead.
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

/** Integer ceiling division: the whole units [numerator] takes up when any remainder rounds up. */
private fun ceilDiv(
    numerator: Long,
    denominator: Long,
): Long = (numerator + denominator - 1) / denominator

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val MINUTES_PER_HOUR = 60L
