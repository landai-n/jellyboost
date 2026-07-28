package dev.jellyfinnative.player.model

/**
 * A point-in-time reading of the player, taken on the main thread and then passed around freely.
 *
 * Reporting and fallback logic both need "where are we, and is it paused" without holding a
 * reference to `Player` — that is what keeps `PlaybackReporter` a plain, virtual-clock-testable
 * class instead of something that needs an ExoPlayer instance.
 */
data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val hasEnded: Boolean = false,
) {
    /** Position in Jellyfin ticks (100 ns units) — the unit every server-side report uses. */
    val positionTicks: Long get() = positionMs.millisToTicks()
}

/** Converts Jellyfin ticks to milliseconds, the unit ExoPlayer seeks in. */
fun Long.ticksToMillis(): Long = this / TICKS_PER_MILLISECOND

/** Converts milliseconds to Jellyfin ticks. */
fun Long.millisToTicks(): Long = this * TICKS_PER_MILLISECOND

/** A Jellyfin tick is 100 nanoseconds, so a millisecond is ten thousand of them. */
private const val TICKS_PER_MILLISECOND = 10_000L
