package dev.jellyboost.player.gesture

/**
 * Zones differ on purpose: volume/brightness split the screen in half, double-tap seek splits it
 * into thirds with a centre dead band (a middle double tap is a fumbled play/pause, not a seek).
 * A swipe starting in an exclusion margin belongs to the system, not to the player.
 */
internal class PlayerGestureController(
    private val config: GestureConfig = GestureConfig(),
) {
    fun swipeTargetFor(
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
    ): SwipeTarget? =
        when {
            widthPx <= 0f || heightPx <= 0f -> null
            yPx < config.verticalExclusionPx || yPx > heightPx - config.verticalExclusionPx -> null
            xPx < config.horizontalExclusionPx || xPx > widthPx - config.horizontalExclusionPx -> null
            xPx > widthPx / 2f -> SwipeTarget.VOLUME
            else -> SwipeTarget.BRIGHTNESS
        }

    /** Negative [dragPx] is upwards, which increases — the direction every player on the platform uses. */
    fun deltaFor(
        dragPx: Float,
        heightPx: Float,
    ): Float {
        if (heightPx <= 0f) return 0f
        return -dragPx / (heightPx * config.fullSwipeRange)
    }

    /** `null` for the dead band in the centre third. */
    fun doubleTapSeekMs(
        xPx: Float,
        widthPx: Float,
    ): Long? {
        if (widthPx <= 0f) return null
        return when {
            xPx < widthPx / THIRDS -> -config.rewindMs
            xPx > widthPx * (THIRDS - 1f) / THIRDS -> config.forwardMs
            else -> null
        }
    }

    private companion object {
        const val THIRDS = 3f
    }
}

internal enum class SwipeTarget {
    BRIGHTNESS,
    VOLUME,
}

/**
 * Distances are pixels the caller has already converted from dp; the reference dp values are 64 dp
 * vertical and 48 dp horizontal (the system's back-gesture zone), and 0.66 is jellyfin-android's
 * `FULL_SWIPE_RANGE_SCREEN_RATIO`.
 *
 * [rewindMs]/[forwardMs] are asymmetric on purpose: they must match the on-screen skip buttons
 * (−10 s / +30 s), not each other.
 */
internal data class GestureConfig(
    val fullSwipeRange: Float = 0.66f,
    val verticalExclusionPx: Float = 0f,
    val horizontalExclusionPx: Float = 0f,
    val rewindMs: Long = 10_000L,
    val forwardMs: Long = 30_000L,
)
