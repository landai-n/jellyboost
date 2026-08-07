package dev.jellyboost.data.downloads.engine

/**
 * Decides which of the 64 KB progress callbacks are worth a Room write.
 *
 * `FileDownloader` reports every 64 KB, which on a fast LAN is several thousand callbacks a second.
 * Room is the single source of truth for progress (docs/PLAN.md, "Download pipeline" → Progress),
 * but writing it that often would turn a download into a database benchmark and make every
 * `DownloadBadge` in the app re-emit at the same rate.
 *
 * So a sample is written when **either** the plan's two conditions holds: at least 500 ms has
 * passed, or the item advanced by at least 1 %. The `or` matters in both directions — the time
 * bound keeps a slow transfer visibly moving, the percentage bound keeps a fast one from jumping
 * from 0 % to 40 % between two ticks.
 *
 * Not thread-safe on purpose: an instance belongs to one *file's* transfer, and a file has exactly
 * one writer — an item's two lanes (`DownloadQueue.transfer`) each create their own as they go.
 */
internal class ProgressThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val fraction: Float = DEFAULT_FRACTION,
) {
    private var lastWriteAt = Long.MIN_VALUE
    private var lastWriteBytes = -1L

    /**
     * @param now monotonic milliseconds; injected rather than read from the clock so the rule is
     *   testable without sleeping.
     * @return `true` when this sample should be written.
     */
    fun shouldWrite(
        bytesDownloaded: Long,
        bytesTotal: Long,
        now: Long,
    ): Boolean {
        // The first sample always lands: it is what flips the row from "queued" to a live number.
        if (lastWriteBytes < 0L) return true
        if (now - lastWriteAt >= intervalMillis) return true
        if (bytesTotal <= 0L) return false
        return (bytesDownloaded - lastWriteBytes) >= bytesTotal * fraction
    }

    /** Records that a sample was written, so the next decision is measured from here. */
    fun recordWrite(
        bytesDownloaded: Long,
        now: Long,
    ) {
        lastWriteAt = now
        lastWriteBytes = bytesDownloaded
    }

    private companion object {
        /** docs/PLAN.md: "throttled Room writes (500ms or 1%)". */
        const val DEFAULT_INTERVAL_MILLIS = 500L
        const val DEFAULT_FRACTION = 0.01f
    }
}
