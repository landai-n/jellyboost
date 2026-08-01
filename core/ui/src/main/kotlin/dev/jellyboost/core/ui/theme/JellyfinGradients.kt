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

    /**
     * The 2026 refresh's hero halo: a much stronger, off-centre accent glow than [BrandGlow], laid
     * behind (or over) a hero backdrop so the top-right of the screen carries colour even where the
     * artwork is dark or missing.
     *
     * Ports the mocks'
     * `radial-gradient(120% 90% at 78% 18%, rgba(170,92,195,.35) 0%, rgba(0,164,220,.16) 42%,
     * transparent 72%)`. CSS sizes a radial gradient's two axes independently; a
     * [RadialGradientShader] has one radius, so the width-derived value is used and the ellipse
     * becomes a circle. That is the right trade here: the halo's ellipticity is imperceptible, but
     * a radius that stopped short of the box would not be — the gradient reaches full transparency
     * at 72% of it, well inside the fill, so no edge of the box can cut a visible ring.
     */
    val HeroHalo: Brush =
        object : ShaderBrush() {
            /** Horizontal centre of the halo, as a fraction of the box width. */
            private val centerXFraction = 0.78f

            /** Vertical centre — high in the box, so the glow reads as coming from above. */
            private val centerYFraction = 0.18f

            /** Radius as a fraction of the box width; the colour stops fade out well within it. */
            private val radiusFraction = 1.0f

            override fun createShader(size: Size): Shader =
                RadialGradientShader(
                    center = Offset(x = size.width * centerXFraction, y = size.height * centerYFraction),
                    radius = size.width * radiusFraction,
                    colors =
                        listOf(
                            JellyfinColors.Secondary.copy(alpha = 0.35f),
                            JellyfinColors.Primary.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                    colorStops = listOf(0f, 0.42f, 0.72f),
                    tileMode = TileMode.Clamp,
                )
        }

    /**
     * The colour a screen with no artwork of its own carries behind its header — the library grid's
     * glow (2026-refresh mocks, "library screen glow").
     *
     * Ports `radial-gradient(80% 100% at 22% 0%, rgba(170,92,195,.17) 0%, transparent 76%)`. Fainter
     * and further to the *start* than [HeroHalo], which sits over a backdrop and has to compete with
     * it; this one is the only colour on the screen and would read as a wash at hero strength. Fill
     * a box anchored to the top of the screen with it — the radius is width-derived, so the box must
     * be at least about as tall as it is wide for the gradient to finish inside it.
     */
    val ScreenGlow: Brush =
        object : ShaderBrush() {
            /** Horizontal centre of the glow, as a fraction of the box width. */
            private val centerXFraction = 0.22f

            /** Radius as a fraction of the box width; the stops fade out at 76% of it. */
            private val radiusFraction = 0.8f

            override fun createShader(size: Size): Shader =
                RadialGradientShader(
                    center = Offset(x = size.width * centerXFraction, y = 0f),
                    radius = size.width * radiusFraction,
                    colors =
                        listOf(
                            JellyfinColors.Secondary.copy(alpha = 0.17f),
                            Color.Transparent,
                        ),
                    colorStops = listOf(0f, 0.76f),
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
