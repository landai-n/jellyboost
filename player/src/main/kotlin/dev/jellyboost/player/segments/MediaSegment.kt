package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind

/**
 * Milliseconds, not the server's ticks: converted once at the edge so no comparison here can be off
 * by a factor of ten thousand.
 *
 * @property startMs first millisecond inside the segment.
 * @property endMs first millisecond *after* the segment — where a skip lands.
 */
internal data class MediaSegment(
    val kind: MediaSegmentKind,
    val startMs: Long,
    val endMs: Long,
) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs

    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
