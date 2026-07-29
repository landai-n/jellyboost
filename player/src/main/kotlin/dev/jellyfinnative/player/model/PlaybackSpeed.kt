package dev.jellyfinnative.player.model

/**
 * The choices in the player's speed picker (docs/PLAN.md, "M9 Polish" → speed).
 *
 * The steps are jellyfin-web's, and so is the lifetime: a speed is **session-scoped and never
 * persisted**. A rate set for one talking-head documentary following the user into the next film
 * would be a setting they did not knowingly make and would struggle to find again — so leaving the
 * player resets it, exactly as the web client does.
 *
 * An enum rather than a raw `Float` because it is a *picker*, and because the UI needs a stable
 * label per step: `0.75f.toString()` is not "0.75×" on every locale.
 */
enum class PlaybackSpeed(
    val rate: Float,
    /** Label as drawn on the control and in the picker; ASCII-safe and locale-independent. */
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

    /** `true` for anything the user chose — the control only shows a speed when it is not normal. */
    val isNormal: Boolean get() = this == NORMAL
}

private const val RATE_HALF = 0.5f
private const val RATE_THREE_QUARTERS = 0.75f
private const val RATE_NORMAL = 1.0f
private const val RATE_ONE_AND_QUARTER = 1.25f
private const val RATE_ONE_AND_HALF = 1.5f
private const val RATE_ONE_AND_THREE_QUARTERS = 1.75f
private const val RATE_DOUBLE = 2.0f
