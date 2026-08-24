package dev.jellyboost.core.ui.theme

import androidx.compose.ui.graphics.Color

/** Lifted from jellyfin-web's dark theme. */
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
     * M3 reserves `outline` for *meaningful* boundaries, so WCAG 1.4.11 asks 3:1 of it. Web's
     * `#3C3C3C` measured 1.72:1 on the background and 1.48:1 on surface; `#6E6E6E` is 3.73:1 and
     * 3.20:1. Decorative seams are `GlassDefaults.Hairline`, not this.
     */
    val Outline = Color(0xFF6E6E6E)
}
