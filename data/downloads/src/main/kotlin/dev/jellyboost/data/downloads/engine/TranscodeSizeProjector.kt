package dev.jellyboost.data.downloads.engine

/**
 * Turns the bytes of an in-flight transcode into a projection of how big the finished file will be.
 *
 * The server cannot tell us — it has not encoded the file yet, and a chunked response carries no
 * `Content-Length` — but what it does send is Matroska, and Matroska says how much media time each
 * byte bought, so
 *
 * ```
 * projectedBytes = bytesReceived × runtimeMillis / mediaMillisReceived
 * ```
 *
 * is the encoder's average output bitrate so far, extended over the whole runtime.
 *
 * The result is clamped into `[bytesReceived, ceiling]`: the file cannot end up smaller than what has
 * already landed, and the projection may only ever *lower* the enqueue-time ceiling, never raise it
 * above what was promised. Early on the ratio is wildly generous and the clamp pins it at the ceiling.
 *
 * @param runtimeMillis must be positive; the queue does not build a projector without it, because
 *   there is nothing to extrapolate to.
 * @param ceilingBytes the enqueue-time estimate, i.e. `DownloadEntity.bytesTotal`.
 */
internal class TranscodeSizeProjector(
    private val runtimeMillis: Long,
    private val ceilingBytes: Long,
    private val scanner: MkvClusterScanner = MkvClusterScanner(),
) {
    /** Feeds bytes straight from the download's copy loop; see [MkvClusterScanner.consume]. */
    fun consume(
        chunk: ByteArray,
        offset: Int = 0,
        length: Int = chunk.size,
    ) = scanner.consume(chunk, offset, length)

    /**
     * The projected finished size of this file, or `null` while there is nothing to go on. `null` is
     * meaningful: it is what keeps a row saying *"up to X"* rather than *"~X"* until the projection is
     * real.
     */
    fun project(bytesReceived: Long): Long? {
        if (bytesReceived <= 0L) return null
        val mediaMillis = scanner.mediaMillisReceived?.takeIf { it > 0L } ?: return null

        val projected = bytesReceived.toDouble() * runtimeMillis / mediaMillis
        // `coerceAtLeast` after `coerceAtMost` on purpose: when the received bytes have already
        // passed the ceiling (an estimate that was too small), the honest answer is the bytes, and
        // the item's own `bytesTotal` grows the same way in `ItemProgress`.
        return projected
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
            .coerceAtMost(ceilingBytes)
            .coerceAtLeast(bytesReceived)
    }
}
