package dev.jellyboost.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JellyfinDarkColorScheme =
    darkColorScheme(
        primary = JellyfinColors.Primary,
        onPrimary = JellyfinColors.OnPrimary,
        secondary = JellyfinColors.Secondary,
        onSecondary = JellyfinColors.OnPrimary,
        background = JellyfinColors.Background,
        onBackground = JellyfinColors.OnBackground,
        surface = JellyfinColors.Surface,
        onSurface = JellyfinColors.OnSurface,
        surfaceVariant = JellyfinColors.SurfaceVariant,
        onSurfaceVariant = JellyfinColors.OnSurfaceVariant,
        error = JellyfinColors.Error,
        onError = JellyfinColors.OnError,
        outline = JellyfinColors.Outline,
    )

/** Default Material 3 type scale; overridden per-style as the design system grows. */
private val JellyfinTypography = Typography()

/**
 * The app theme. Dark only by design — jellyfin-web's dark theme is the reference and a light
 * scheme is explicitly out of scope for v1 (docs/PLAN.md).
 */
@Composable
fun JellyfinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JellyfinDarkColorScheme,
        typography = JellyfinTypography,
        content = content,
    )
}
