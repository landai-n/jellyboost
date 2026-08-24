package dev.jellyboost.player.model

/**
 * The steps are jellyfin-web's, and so is the lifetime: a speed is **session-scoped and never
 * persisted** — leaving the player resets it.
 */
internal enum class PlaybackSpeed(
    val rate: Float,
    /** Hardcoded because `0.75f.toString()` is not "0.75" on every locale. */
    val label: String,
) {
    HALF(RATE_HALF, "0.5×"),
    THREE_QUARTERS(RATE_THREE_QUARTERS, "0.75×"),
    NORMAL(RATE_NORMAL, "1×"),
    ONE_AND_QUARTER(RATE_ONE_AND_QUARTER, "1.25×"),
    ONE_AND_HALF(RATE_ONE_AND_HALF, "1.5×"),
    ONE_AND_THREE_QUARTERS(RATE_ONE_AND_THREE_QUARTERS, "1.75×"),
    DOUBLE(RATE_DOUBLE, "2×"),
    ;

    val isNormal: Boolean get() = this == NORMAL
}

private const val RATE_HALF = 0.5f
private const val RATE_THREE_QUARTERS = 0.75f
private const val RATE_NORMAL = 1.0f
private const val RATE_ONE_AND_QUARTER = 1.25f
private const val RATE_ONE_AND_HALF = 1.5f
private const val RATE_ONE_AND_THREE_QUARTERS = 1.75f
private const val RATE_DOUBLE = 2.0f
