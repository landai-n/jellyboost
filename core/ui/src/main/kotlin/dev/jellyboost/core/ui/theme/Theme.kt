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

private val JellyfinTypography = Typography()

/**
 * Mirrors [Dimens]: `small` = `MPillRadius`, `medium` = `CardCornerRadius`, `large` = `PanelRadius`,
 * `extraLarge` = `RadiusXl`. `extraSmall` keeps its M3 default deliberately.
 */
private val JellyfinShapes =
    Shapes(
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

/**
 * Dark only by design; a light scheme is out of scope for v1.
 *
 * **Do not add a `MotionDurationScale` provider here.** Verified against compose-ui /
 * animation-core 1.11.4: `setContent`'s window recomposer already puts a `MotionDurationScaleImpl`
 * in its effect context, observing `Settings.Global.ANIMATOR_DURATION_SCALE`, and `animation-core`
 * reads it on every animation. Providing our own would *replace* the platform-observing one.
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
