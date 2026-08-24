package dev.jellyboost.player.gesture

/**
 * The arithmetic behind the player's touch gestures, with no Android type in sight.
 *
 * Modelled on jellyfin-android's `PlayerGestureHelper` — same zones, same swipe range, same
 * exclusion margins — but split so that the decisions are testable and the Compose layer that feeds
 * it coordinates stays a handful of lines.
 *
 * Two things here are easy to get wrong and are therefore pinned by tests rather than by eye:
 *
 * - **which zone a touch is in.** Volume and brightness split the screen down the middle, but the
 *   double-tap seek splits it into *thirds* with a dead band in the centre — a double tap in the
 *   middle of the screen is a fumbled play/pause, not a request to seek thirty seconds.
 * - **the exclusion margins.** A swipe that begins inside the system's back-gesture strip or against
 *   the top/bottom edge belongs to the system, and claiming it makes the player feel broken in a way
 *   the user will blame on the app.
 */
internal class PlayerGestureController(
    private val config: GestureConfig = GestureConfig(),
) {
    /** Where a vertical swipe that started at [xPx] sends its delta, or `null` if it is excluded. */
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

    /**
     * The fraction of the full 0..1 range a drag of [dragPx] represents.
     *
     * Negative [dragPx] is upwards, which increases — the direction every player on the platform
     * uses. A full sweep is [GestureConfig.fullSwipeRange] of the screen's height, so the gesture is
     * usable one-handed without demanding pixel precision.
     */
    fun deltaFor(
        dragPx: Float,
        heightPx: Float,
    ): Float {
        if (heightPx <= 0f) return 0f
        return -dragPx / (heightPx * config.fullSwipeRange)
    }

    /** How far a double tap at [xPx] should seek, or `null` for the dead band in the centre. */
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

/** Which of the two vertical-swipe gestures a touch drives. */
internal enum class SwipeTarget {
    BRIGHTNESS,
    VOLUME,
}

/**
 * The gesture tuning, in pixels the caller has already converted from dp.
 *
 * @property fullSwipeRange fraction of the screen height a full 0→1 sweep takes; 0.66 matches
 *   jellyfin-android's `FULL_SWIPE_RANGE_SCREEN_RATIO`.
 * @property verticalExclusionPx dead strip along the top and bottom edges (64 dp there).
 * @property horizontalExclusionPx dead strip along the left and right edges — the system's
 *   back-gesture zone (48 dp there).
 * @property rewindMs / @property forwardMs the double-tap seek amounts. They deliberately match the
 *   on-screen skip buttons (−10 s / +30 s) rather than being symmetric: two controls that claim to
 *   do the same thing and do not would be worse than an asymmetry the user can see on the buttons.
 */
internal data class GestureConfig(
    val fullSwipeRange: Float = 0.66f,
    val verticalExclusionPx: Float = 0f,
    val horizontalExclusionPx: Float = 0f,
    val rewindMs: Long = 10_000L,
    val forwardMs: Long = 30_000L,
)
