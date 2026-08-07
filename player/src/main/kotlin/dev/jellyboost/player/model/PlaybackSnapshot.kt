package dev.jellyboost.player.model

import dev.jellyboost.core.common.Ticks

/**
 * A point-in-time reading of the player, taken on the main thread and then passed around freely.
 *
 * Reporting and fallback logic both need "where are we, and is it paused" without holding a
 * reference to `Player` — that is what keeps `PlaybackReporter` a plain, virtual-clock-testable
 * class instead of something that needs an ExoPlayer instance.
 *
 * @property isValid whether the reading describes **this session's own media**. A local player can
 *   only ever play what it was prepared with, so its snapshots are always valid; a *cast* player
 *   mirrors a receiver that anything on the network may unload or reload, and a reading taken after
 *   that happened is a position — usually zero — that belongs to nothing of ours. `PlaybackReporter`
 *   refuses to write an invalid snapshot's position anywhere (audit CAST-01).
 */
data class PlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val hasEnded: Boolean = false,
    val isValid: Boolean = true,
) {
    /** Position in Jellyfin ticks (100 ns units) — the unit every server-side report uses. */
    val positionTicks: Long get() = Ticks.millisToTicks(positionMs)
}

/**
 * Converts Jellyfin ticks to milliseconds, the unit ExoPlayer seeks in.
 *
 * Thin wrapper around [Ticks.ticksToMillis] (DUP-6) kept here, rather than switching every call
 * site to `Ticks.ticksToMillis(x)` directly, because `SyncPlayController.kt` — frozen for a later
 * wave — imports this extension by its `dev.jellyboost.player.model` package name; changing that
 * import would count as editing that file.
 */
fun Long.ticksToMillis(): Long = Ticks.ticksToMillis(this)

/** See [ticksToMillis]; converts milliseconds to Jellyfin ticks. */
fun Long.millisToTicks(): Long = Ticks.millisToTicks(this)
