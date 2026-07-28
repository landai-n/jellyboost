package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.model.DownloadItem

/**
 * Derives a transfer speed from successive Room emissions.
 *
 * The plan asks the queue tab for a speed, and the pipeline deliberately does not store one: a
 * bytes-per-second column would be a second, staler source of truth next to `bytesDownloaded`, and
 * it would have to be written on every throttled progress update. Two consecutive samples of a
 * counter that is already there give the same answer for free.
 *
 * The result is smoothed with an exponential moving average, because raw sample-to-sample deltas
 * over a ~500 ms throttle window jump around enough to make the number unreadable.
 *
 * Not thread-safe; one instance belongs to one ViewModel.
 */
class DownloadSpeedTracker(
    private val smoothing: Float = DEFAULT_SMOOTHING,
) {
    private data class Sample(
        val bytes: Long,
        val atMillis: Long,
    )

    private val previous = mutableMapOf<String, Sample>()
    private val smoothed = mutableMapOf<String, Long>()

    /**
     * Folds a fresh snapshot in and returns the current speed per item, in bytes per second.
     *
     * @param nowMillis wall-clock milliseconds; a parameter so the rule is testable without
     *   sleeping.
     */
    fun update(
        items: List<DownloadItem>,
        nowMillis: Long,
    ): Map<String, Long> {
        val active = items.filter { it.status == DownloadStatus.DOWNLOADING }
        val activeIds = active.map { it.itemId }.toSet()

        // An item that stopped downloading must not keep reporting the speed it had when it did.
        previous.keys.retainAll(activeIds)
        smoothed.keys.retainAll(activeIds)

        for (item in active) {
            val last = previous[item.itemId]
            previous[item.itemId] = Sample(item.bytesDownloaded, nowMillis)
            val elapsed = nowMillis - (last?.atMillis ?: nowMillis)

            // Nothing to measure from a first sample, or from two samples at the same instant.
            if (last != null && elapsed > 0L) {
                smoothed[item.itemId] = fold(item.itemId, item.bytesDownloaded - last.bytes, elapsed)
            }
        }

        return smoothed.toMap()
    }

    /**
     * Folds one measurement into the running average.
     *
     * A negative [delta] means the file restarted — a server that ignored the `Range` header makes
     * the byte counter go backwards — and reporting a negative speed would be worse than none.
     */
    private fun fold(
        itemId: String,
        delta: Long,
        elapsedMillis: Long,
    ): Long {
        val instant = delta.coerceAtLeast(0L) * MILLIS_PER_SECOND / elapsedMillis
        val current = smoothed[itemId] ?: return instant
        return (current + smoothing * (instant - current)).toLong()
    }

    private companion object {
        /** How much of each new sample is folded in; lower is steadier, slower to react. */
        const val DEFAULT_SMOOTHING = 0.3f
        const val MILLIS_PER_SECOND = 1_000L
    }
}
