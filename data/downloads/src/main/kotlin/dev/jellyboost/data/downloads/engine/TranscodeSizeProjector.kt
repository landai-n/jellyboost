package dev.jellyboost.data.downloads.engine

/**
 * Turns the bytes of an in-flight transcode into a projection of how big the finished file will be.
 *
 * The server cannot tell us: it has not encoded the file yet, and a chunked response carries no
 * `Content-Length` (docs/features/download-quality.md, "No exact size"). What it *does* send is
 * Matroska, and Matroska says how much media time each byte bought — so
 *
 * ```
 * projectedBytes = bytesReceived × runtimeMillis / mediaMillisReceived
 * ```
 *
 * which is the encoder's average output bitrate so far, extended over the whole runtime. It is the
 * same quantity ffmpeg reports about itself, computed on this side from the bytes we already have
 * to copy anyway: no extra request, no session bookkeeping, no server-version assumption
 * (docs/notes/download-size-estimation.md).
 *
 * ### It can only ever be an improvement
 * The result is clamped into `[bytesReceived, ceiling]`. The lower bound is arithmetic honesty —
 * the file cannot end up smaller than what has already landed. The upper bound is the enqueue-time
 * estimate, the deterministic ceiling `DownloadEnqueuer` computed from runtime × min(cap, source
 * bitrate); the projection is allowed to *lower* the figure the user sees as evidence arrives, and
 * never to raise it above what was promised. Early on — a few hundred milliseconds of media, most
 * of it container headers — the ratio is wildly generous and the clamp simply pins it at the
 * ceiling, which is exactly today's behaviour. It leaves the ceiling only once it has something
 * better to say.
 *
 * @param runtimeMillis the item's runtime from `BaseItemDto.runTimeTicks`. Must be positive; the
 *   queue does not build a projector without it, because there is nothing to extrapolate to.
 * @param ceilingBytes the enqueue-time estimate, i.e. `DownloadEntity.bytesTotal`.
 */
class TranscodeSizeProjector(
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
     * The projected finished size of this file, or `null` while there is nothing to go on — before
     * the first cluster timestamp has been read, or before any bytes have landed.
     *
     * `null` is meaningful: it is what keeps a row saying *"up to X"* rather than *"~X"* until the
     * projection is real.
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
