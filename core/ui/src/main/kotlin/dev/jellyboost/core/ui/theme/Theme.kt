package dev.jellyboost.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.ThemeMode

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

private val JellyfinLightColorScheme =
    lightColorScheme(
        primary = JellyfinLightColors.Primary,
        onPrimary = JellyfinLightColors.OnPrimary,
        secondary = JellyfinLightColors.Secondary,
        onSecondary = JellyfinLightColors.OnPrimary,
        background = JellyfinLightColors.Background,
        onBackground = JellyfinLightColors.OnBackground,
        surface = JellyfinLightColors.Surface,
        onSurface = JellyfinLightColors.OnSurface,
        surfaceVariant = JellyfinLightColors.SurfaceVariant,
        onSurfaceVariant = JellyfinLightColors.OnSurfaceVariant,
        error = JellyfinLightColors.Error,
        onError = JellyfinLightColors.OnError,
        outline = JellyfinLightColors.Outline,
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
 * Whether the *drawn* scheme is a light one, for the tokens M3's `ColorScheme` has no role for —
 * glass fills, hairlines, the ink of a disabled label. Those cannot be derived from a role: a glass
 * fill gets *lighter* in light mode while a hairline flips to black, so one bit is what they branch
 * on. Read it through [GlassDefaults] / [pageInk] rather than directly.
 *
 * `static`: it changes once per theme switch, and every glass surface in the app reads it.
 */
internal val LocalIsLightTheme = staticCompositionLocalOf { false }

/**
 * Whether this mode draws the dark scheme *right now*. Public, and the only place the question is
 * answered: the window's system-bar icon appearance is set outside the composition [JellyfinTheme]
 * establishes, and a second `when` over [ThemeMode] there is how the two would drift apart.
 */
@Composable
@ReadOnlyComposable
fun ThemeMode.resolvesDark(): Boolean =
    when (this) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

/**
 * @param dynamicColor Material You. It replaces the brand primary while it is on, which is why it
 *   defaults to off (DECISIONS 2026-08-01, 2026-08-28) — and why it is ignored below API 31, where
 *   the platform has no wallpaper palette to derive from.
 *
 * **Do not add a `MotionDurationScale` provider here.** Verified against compose-ui /
 * animation-core 1.11.4: `setContent`'s window recomposer already puts a `MotionDurationScaleImpl`
 * in its effect context, observing `Settings.Global.ANIMATOR_DURATION_SCALE`, and `animation-core`
 * reads it on every animation. Providing our own would *replace* the platform-observing one.
 */
@Composable
fun JellyfinTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.resolvesDark()
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            dark -> JellyfinDarkColorScheme
            else -> JellyfinLightColorScheme
        }

    CompositionLocalProvider(LocalIsLightTheme provides !dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JellyfinTypography,
            shapes = JellyfinShapes,
            content = content,
        )
    }
}
