package dev.jellyboost.data.downloads.engine

/**
 * Decides which of the 64 KB progress callbacks are worth a Room write. `FileDownloader` reports every
 * 64 KB, which on a fast LAN is several thousand callbacks a second, and Room is the single source of
 * truth for progress.
 *
 * A sample is written when **either** of two conditions holds: at least 500 ms has passed, or the item
 * advanced by at least 1 %. The `or` matters in both directions — the time bound keeps a slow transfer
 * visibly moving, the percentage bound keeps a fast one from jumping 0 % to 40 % between two ticks.
 *
 * Not thread-safe on purpose: an instance belongs to one *file's* transfer, which has one writer.
 */
internal class ProgressThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val fraction: Float = DEFAULT_FRACTION,
) {
    private var lastWriteAt = Long.MIN_VALUE
    private var lastWriteBytes = -1L

    /** @param now monotonic milliseconds, injected so the rule is testable without sleeping. */
    fun shouldWrite(
        bytesDownloaded: Long,
        bytesTotal: Long,
        now: Long,
    ): Boolean =
        when {
            // The first sample always lands: it is what flips the row from "queued" to a live number.
            lastWriteBytes < 0L -> true
            now - lastWriteAt >= intervalMillis -> true
            // Nothing to measure a percentage against until the server declares a length.
            bytesTotal <= 0L -> false
            else -> (bytesDownloaded - lastWriteBytes) >= bytesTotal * fraction
        }

    fun recordWrite(
        bytesDownloaded: Long,
        now: Long,
    ) {
        lastWriteAt = now
        lastWriteBytes = bytesDownloaded
    }

    private companion object {
        /** Throttled Room writes: 500 ms, or 1 %. */
        const val DEFAULT_INTERVAL_MILLIS = 500L
        const val DEFAULT_FRACTION = 0.01f
    }
}
