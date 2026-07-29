package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.model.DownloadItem

/**
 * Derives a transfer speed from successive Room emissions.
 *
 * The plan asks the queue tab for a speed, and the pipeline deliberately does not store one: a
 * bytes-per-second column would be a second, staler source of truth next to `bytesDownloaded`, and
 * it would have to be written on every throttled progress update. Two samples of a counter that is
 * already there give the same answer for free.
 *
 * ### Why a measurement window, and not simply the last two emissions
 * The emissions are not the progress writes. `DownloadDao.observeAll` is a `@Transaction` over
 * `downloads` *and* `download_files`, so it re-emits for the file-level write as well as the
 * item-level one — and `DownloadQueue` writes those back to back, milliseconds apart. Dividing a
 * whole throttle window's worth of bytes by the few milliseconds between two halves of one write
 * reported 100–180 MB/s for a transfer actually running at 2–8 MB/s (docs/POLISH.md).
 *
 * So a sample is only *folded in* once at least [windowMillis] has passed since the last one; until
 * then the bytes accumulate against the same anchor and the previous answer stands. Rapid emissions
 * therefore cost nothing and cannot inflate anything, and the denominator is always a real second
 * of wall clock rather than an artefact of how Room batches its notifications.
 *
 * The result is additionally smoothed with an exponential moving average, because a transfer's true
 * rate over one second still jumps around enough to make the number unreadable.
 *
 * Not thread-safe; one instance belongs to one ViewModel.
 */
class DownloadSpeedTracker(
    private val smoothing: Float = DEFAULT_SMOOTHING,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private data class Sample(
        val bytes: Long,
        val atMillis: Long,
    )

    /** The last sample each item's next measurement is taken *from*. */
    private val anchors = mutableMapOf<String, Sample>()
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
        anchors.keys.retainAll(activeIds)
        smoothed.keys.retainAll(activeIds)

        active.forEach { item -> sample(item, nowMillis) }

        return smoothed.toMap()
    }

    /**
     * Takes one item's measurement, if enough time has passed to make one worth taking.
     *
     * Nothing is measurable from a first sample; and a sample taken before the window is up is not
     * discarded — leaving the anchor where it is means its bytes count towards the next
     * measurement instead.
     */
    private fun sample(
        item: DownloadItem,
        nowMillis: Long,
    ) {
        val anchor = anchors[item.itemId]
        if (anchor == null) {
            anchors[item.itemId] = Sample(item.bytesDownloaded, nowMillis)
            return
        }

        val elapsed = nowMillis - anchor.atMillis
        if (elapsed < windowMillis) return

        anchors[item.itemId] = Sample(item.bytesDownloaded, nowMillis)
        smoothed[item.itemId] = fold(item.itemId, item.bytesDownloaded - anchor.bytes, elapsed)
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

        /**
         * The shortest interval a rate may be measured over.
         *
         * One second: long enough that it always spans several of Room's emissions (the progress
         * writes themselves are throttled to 500 ms or 1 %), and short enough that the number on
         * screen still reacts to a link that slows down.
         */
        const val DEFAULT_WINDOW_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
