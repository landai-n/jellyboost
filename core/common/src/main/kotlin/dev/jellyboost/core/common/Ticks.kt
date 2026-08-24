package dev.jellyboost.core.common

/**
 * A Jellyfin tick is 100 nanoseconds — the unit every server-side duration and position field uses on the
 * wire. One shared object so the player, the download engine and the item model share the arithmetic.
 */
object Ticks {
    const val PER_MILLISECOND = 10_000L

    const val PER_SECOND = 10_000_000L

    const val PER_MINUTE = 600_000_000L

    fun ticksToMillis(ticks: Long): Long = ticks / PER_MILLISECOND

    fun millisToTicks(millis: Long): Long = millis * PER_MILLISECOND

    fun ticksToMinutes(ticks: Long): Int = (ticks / PER_MINUTE).toInt()

    /** `null` when absent or too small to reach a whole millisecond — the guard before dividing by a runtime. */
    fun positiveMillisOrNull(ticks: Long?): Long? = ticks?.div(PER_MILLISECOND)?.takeIf { it > 0L }
}
