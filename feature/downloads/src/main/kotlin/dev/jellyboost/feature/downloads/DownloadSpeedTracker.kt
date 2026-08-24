package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem

/**
 * Derives a transfer speed from successive Room emissions; the pipeline deliberately stores no
 * bytes-per-second column, which would be a staler second source of truth beside `bytesDownloaded`.
 *
 * The [windowMillis] gate is **not** optional. Emissions are not progress writes:
 * `DownloadDao.observeAll` is a `@Transaction` over `downloads` *and* `download_files`, and
 * `DownloadQueue` writes those back to back, milliseconds apart. Dividing a whole throttle window's
 * bytes by the gap between two halves of one write reported 100–180 MB/s for a 2–8 MB/s transfer.
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

    /** The anchor each item's next measurement is taken *from*, not simply the last emission. */
    private val anchors = mutableMapOf<String, Sample>()
    private val smoothed = mutableMapOf<String, Long>()

    /** @param nowMillis wall-clock ms; a parameter so the window rule is testable without sleeping. */
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
     * A sample taken before the window is up is **not** discarded: leaving the anchor where it is
     * means its bytes count towards the next measurement instead.
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
     * A negative [delta] means the file restarted — a server that ignored `Range` makes the byte
     * counter go backwards — and a negative speed would be worse than none.
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
         * Long enough to span several of Room's emissions — the progress writes themselves are
         * throttled to 500 ms or 1 % — and short enough to react to a link that slows down.
         */
        const val DEFAULT_WINDOW_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
