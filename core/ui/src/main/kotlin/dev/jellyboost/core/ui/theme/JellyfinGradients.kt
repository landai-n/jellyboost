package dev.jellyboost.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode

/**
 * The jellyfin-web accent gradient (`#AA5CC3 → #00A4DC`) and the scrims the design system uses to
 * keep text legible on top of artwork (docs/PLAN.md, ":core:ui").
 */
object JellyfinGradients {
    /** Left-to-right accent sweep — used for emphasis bars, primary buttons and badges. */
    val Accent: Brush =
        Brush.horizontalGradient(
            colors = listOf(JellyfinColors.Secondary, JellyfinColors.Primary),
        )

    /** Top-left to bottom-right variant, for larger surfaces where a diagonal reads better. */
    val AccentDiagonal: Brush =
        Brush.linearGradient(
            colors = listOf(JellyfinColors.Secondary, JellyfinColors.Primary),
        )

    /** Transparent → background scrim laid over a backdrop so titles stay readable. */
    val BackdropScrim: Brush =
        Brush.verticalGradient(
            colors =
                listOf(
                    Color.Transparent,
                    JellyfinColors.Background.copy(alpha = 0.65f),
                    JellyfinColors.Background,
                ),
        )

    /**
     * Faint accent halo for hero areas that have no artwork of their own — the auth screens'
     * branded header being the first user.
     *
     * It is a [ShaderBrush] rather than a plain `Brush.radialGradient` because the glow has to be
     * anchored near the top edge of whatever box it fills and reach past the far corner; both
     * depend on the measured size, which only a shader brush gets to see.
     */
    val BrandGlow: Brush =
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                RadialGradientShader(
                    center = Offset(x = size.width / 2f, y = size.height * 0.08f),
                    radius = size.maxDimension * 0.85f,
                    colors =
                        listOf(
                            JellyfinColors.Secondary.copy(alpha = 0.20f),
                            JellyfinColors.Primary.copy(alpha = 0.09f),
                            Color.Transparent,
                        ),
                    colorStops = listOf(0f, 0.45f, 1f),
                    tileMode = TileMode.Clamp,
                )
        }

    /** Placeholder fill for artwork that has not loaded (or does not exist on the server). */
    val ImagePlaceholder: Brush =
        Brush.linearGradient(
            colors =
                listOf(
                    JellyfinColors.SurfaceVariant,
                    JellyfinColors.Surface,
                ),
        )
}
