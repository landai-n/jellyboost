package dev.jellyboost.player.gesture

import kotlin.math.pow

/**
 * A window's `screenBrightness` is a backlight fraction, and a panel maps it close to linear in
 * nits with the top of the range reserved for high-brightness mode — on the test tablet 0.5 already
 * reaches 500 of its 600 nits, and everything above it is clamped until sunlight engages HBM. The
 * eye answers roughly to the cube root of luminance, so a fraction written straight through spends
 * four fifths of its travel on steps too small to see.
 *
 * The conversions are the CIE L* transfer pair, so equal travel is equal perceived change; they are
 * exact inverses, which is what lets a swipe and the Display slider read the same level back.
 */
internal object BrightnessCurve {
    /** Perceived level (what the user moves, and what the indicator shows) to backlight fraction. */
    fun toBacklight(fraction: Float): Float {
        val lightness = fraction.coerceIn(0f, 1f) * LIGHTNESS_MAX
        return if (lightness > LIGHTNESS_KNEE) {
            ((lightness + LIGHTNESS_OFFSET) / LIGHTNESS_SCALE).pow(CUBE)
        } else {
            lightness / KAPPA
        }
    }

    /** Backlight fraction — a window attribute or a system setting — back to a perceived level. */
    fun toFraction(backlight: Float): Float {
        val luminance = backlight.coerceIn(0f, 1f)
        val lightness =
            if (luminance > LUMINANCE_KNEE) {
                LIGHTNESS_SCALE * luminance.pow(1f / CUBE) - LIGHTNESS_OFFSET
            } else {
                luminance * KAPPA
            }
        return (lightness / LIGHTNESS_MAX).coerceIn(0f, 1f)
    }

    private const val CUBE = 3f
    private const val LIGHTNESS_MAX = 100f
    private const val LIGHTNESS_OFFSET = 16f
    private const val LIGHTNESS_SCALE = 116f

    /** Below the knee L* is linear in luminance; above it, cubic. */
    private const val LIGHTNESS_KNEE = 8f
    private const val LUMINANCE_KNEE = 0.008856f
    private const val KAPPA = 903.3f
}
