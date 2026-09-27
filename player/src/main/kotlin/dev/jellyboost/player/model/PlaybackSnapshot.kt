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

/**
 * `true` for a reading that claims position 0 while [lastValid] vouched for a later one. That is what
 * a player that has let go of the item answers while still counting as valid: media3's
 * `RemoteCastPlayer` once its session is torn down (it drops the client but keeps its timeline, so our
 * item still looks held, at a stale or zero position), and the idle local player routing falls back to.
 * Such a reading is never evidence of where the film is and must reach no report. A real return to the
 * start is the user's seek, which moves [lastValid] to 0 first; an ended reading is exempt.
 */
internal fun PlaybackSnapshot.contradicts(lastValid: PlaybackSnapshot?): Boolean =
    isValid && !hasEnded && positionMs == 0L && (lastValid?.positionMs ?: 0L) > 0L
