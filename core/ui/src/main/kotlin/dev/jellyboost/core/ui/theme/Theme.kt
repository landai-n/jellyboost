package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
 * The refresh's radii, mapped onto the M3 shape roles so components that take their corners from
 * the theme (buttons, chips, cards, menus) round the same way as the ones that name a [Dimens]
 * value directly: `medium` is [Dimens.CardCornerRadius], `large` is [Dimens.PanelRadius] and
 * `extraLarge` is [Dimens.RadiusXl]. `small` (6dp) is the mocks' mini-badge corner,
 * [Dimens.MPillRadius].
 *
 * `extraSmall` keeps its M3 default: nothing in the refresh is that tightly rounded, and leaving it
 * alone means the one role we have no opinion about still behaves like stock Material.
 */
private val JellyfinShapes =
    Shapes(
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

/**
 * The app theme. Dark only by design — jellyfin-web's dark theme is the reference and a light
 * scheme is explicitly out of scope for v1 (docs/PLAN.md).
 */
@Composable
fun JellyfinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JellyfinDarkColorScheme,
        typography = JellyfinTypography,
        shapes = JellyfinShapes,
        content = content,
    )
}
