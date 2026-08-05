package dev.jellyboost.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette lifted from jellyfin-web's dark theme so the native client reads as the same product.
 * See docs/PLAN.md, ":core:ui".
 */
internal object JellyfinColors {
    val Background = Color(0xFF101010)
    val Surface = Color(0xFF202020)
    val SurfaceVariant = Color(0xFF292929)
    val Primary = Color(0xFF00A4DC)
    val Secondary = Color(0xFFAA5CC3)
    val OnPrimary = Color(0xFF000000)
    val OnBackground = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceVariant = Color(0xB3FFFFFF)
    val Error = Color(0xFFCF6679)
    val OnError = Color(0xFF000000)

    /**
     * The M3 `outline` role: dividers, and the box around an inline metadata badge.
     *
     * Lightened from jellyfin-web's `#3C3C3C` by the 2026-08-05 accessibility audit. `outline` is
     * the role M3 reserves for *meaningful* boundaries, so WCAG 1.4.11 asks 3:1 of it; `#3C3C3C`
     * was 1.72:1 on the `#101010` background and 1.48:1 on `#202020` — an `MPillBadge`'s border was
     * the only thing separating "TV-MA" from the sentence of dots and years around it, and it was
     * effectively invisible. `#6E6E6E` is 3.73:1 on the background and 3.20:1 on surface. Faint
     * *decorative* seams are not this token — those are `GlassDefaults.Hairline` and friends.
     */
    val Outline = Color(0xFF6E6E6E)
}
