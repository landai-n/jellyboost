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
     * anchored near the top edge of whatever box it fills and fade out *exactly at the box's
     * bottom edge* — a radius derived from anything but the measured height leaves the gradient
     * still visible where the box ends, which reads as a hard seam across the background (seen on
     * the tablet in landscape, where the width-driven radius dwarfed the box height).
     */
    val BrandGlow: Brush =
        object : ShaderBrush() {
            /** How far down the box the glow's centre sits, as a fraction of its height. */
            private val centerYFraction = 0.08f

            override fun createShader(size: Size): Shader {
                val centerY = size.height * centerYFraction
                return RadialGradientShader(
                    center = Offset(x = size.width / 2f, y = centerY),
                    radius = size.height - centerY,
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
        }

    /**
     * [BrandGlow]'s sibling for the side-by-side auth layout: the halo hangs over the *start*
     * pane (centre at 28% of the width, top edge) instead of the middle of the window. Fill a
     * full-bleed box with it — the radius is sized so the gradient reaches zero before any
     * interior edge, so it must never be cut off by a smaller box.
     */
    val BrandGlowSide: Brush =
        object : ShaderBrush() {
            /** Horizontal centre of the halo, as a fraction of the box width. */
            private val centerXFraction = 0.28f

            /** Radius as a fraction of the box width — fades out before reaching the far side. */
            private val radiusFraction = 0.55f

            override fun createShader(size: Size): Shader =
                RadialGradientShader(
                    center = Offset(x = size.width * centerXFraction, y = 0f),
                    radius = size.width * radiusFraction,
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
