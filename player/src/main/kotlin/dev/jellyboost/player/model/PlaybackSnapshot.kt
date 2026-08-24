package dev.jellyboost.player.model

import dev.jellyboost.core.common.Ticks

/**
 * Taken on the main thread, then passed around freely.
 *
 * @property isValid whether the reading describes **this session's own media**. Always `true` for a
 *   local player; a cast player mirrors a receiver anything on the network may reload, and such a
 *   reading is a position belonging to nothing of ours. `PlaybackReporter` refuses to write one.
 */
data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val hasEnded: Boolean = false,
    val isValid: Boolean = true,
) {
    /** Jellyfin ticks (100 ns units) — the unit every server-side report uses. */
    val positionTicks: Long get() = Ticks.millisToTicks(positionMs)
}

internal fun Long.ticksToMillis(): Long = Ticks.ticksToMillis(this)

internal fun Long.millisToTicks(): Long = Ticks.millisToTicks(this)
