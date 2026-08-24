package dev.jellyboost.core.common

/**
 * Conversions for Jellyfin's tick-based durations — a tick is 100 nanoseconds, the unit every
 * server-side duration and position field (`runTimeTicks`, `positionTicks`, …) uses on the wire.
 *
 * One shared object rather than one set of constants and converters per module: player playback
 * math, the download engine's size projections and the item model's runtime labels all do the same
 * millisecond/minute arithmetic, so they all call through here instead of under their own names.
 */
object Ticks {
    /** A Jellyfin tick is 100 nanoseconds, so a millisecond is ten thousand of them. */
    const val PER_MILLISECOND = 10_000L

    /** Ticks in a whole second (`PER_MILLISECOND * 1000`). */
    const val PER_SECOND = 10_000_000L

    /** Ticks in a whole minute (`PER_MILLISECOND * 1000 * 60`). */
    const val PER_MINUTE = 600_000_000L

    /** Converts Jellyfin ticks to milliseconds, the unit ExoPlayer seeks in. */
    fun ticksToMillis(ticks: Long): Long = ticks / PER_MILLISECOND

    /** Converts milliseconds to Jellyfin ticks. */
    fun millisToTicks(millis: Long): Long = millis * PER_MILLISECOND

    /** Ticks rounded down to whole minutes — a runtime or a remaining-time label. */
    fun ticksToMinutes(ticks: Long): Int = (ticks / PER_MINUTE).toInt()

    /**
     * [ticks] converted to milliseconds, or `null` when absent or too small to reach a whole
     * millisecond — the guard the download engine's size-projection call sites need before
     * dividing a possibly-missing `runTimeTicks`.
     */
    fun positiveMillisOrNull(ticks: Long?): Long? = ticks?.div(PER_MILLISECOND)?.takeIf { it > 0L }
}
