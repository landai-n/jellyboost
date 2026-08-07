package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind

/**
 * One intro or outro range of the item being played, in milliseconds.
 *
 * Milliseconds rather than the server's ticks because everything that compares against this — the
 * player's position, the seek it performs — is in milliseconds, and converting once at the edge is
 * what keeps a factor of ten thousand from appearing in the middle of a comparison.
 *
 * @property startMs first millisecond inside the segment.
 * @property endMs first millisecond *after* the segment — where a skip lands.
 */
internal data class MediaSegment(
    val kind: MediaSegmentKind,
    val startMs: Long,
    val endMs: Long,
) {
    /** `true` when [positionMs] falls inside this segment. */
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs

    /** How long the segment lasts. */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
