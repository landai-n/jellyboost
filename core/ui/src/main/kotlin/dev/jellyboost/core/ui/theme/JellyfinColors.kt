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
    val Outline = Color(0xFF3C3C3C)
}
