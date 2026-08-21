package dev.jellyboost.core.ui.theme

import android.graphics.Paint
import android.graphics.RadialGradient
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

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
     * The protective band the app's floating top chrome is read against — background@94% at the very
     * top of the window, fading out by the bottom edge of the bar. Strong on purpose: white section
     * titles scroll directly behind the brand mark, and at 80%/45% they still read through it
     * (seen on the tablet walk).
     *
     * [BackdropScrim] runs the other way (transparent at the top, solid at the bottom) because it
     * exists to seat a hero *into* the page below it; that leaves the top of the window — exactly
     * where the brand mark, the tab capsule and the app-wide actions float — the least protected
     * part of a full-bleed backdrop. This brush is the counterpart: it is drawn as a sibling *over*
     * the page and *under* the bars, never inside a `hazeSource` and never inside a `hazeEffect`,
     * since Haze samples a backdrop rather than another effect
     * (DECISIONS.md 2026-08-01, chrome readability).
     */
    val TopChromeScrim: Brush =
        Brush.verticalGradient(
            colors =
                listOf(
                    JellyfinColors.Background.copy(alpha = 0.94f),
                    JellyfinColors.Background.copy(alpha = 0.72f),
                    Color.Transparent,
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

    /** Horizontal centre of [screenGlow], as a fraction of the box width. */
    internal const val SCREEN_GLOW_CENTER_X_FRACTION = 0.22f

    /** [screenGlow]'s radius as a fraction of the box width. */
    internal const val SCREEN_GLOW_RADIUS_FRACTION = 0.8f

    /** Where [screenGlow]'s fade reaches full transparency, as a fraction of its radius. */
    internal const val SCREEN_GLOW_FADE_STOP = 0.76f

    /** The glow's peak alpha at its centre. */
    internal const val SCREEN_GLOW_ALPHA = 0.17f

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

/**
 * The colour a screen with no artwork of its own carries behind its header — the library grid's
 * glow (2026-refresh mocks, "library screen glow").
 *
 * Ports `radial-gradient(80% 100% at 22% 0%, rgba(170,92,195,.17) 0%, transparent 76%)`. Fainter
 * and further to the *start* than [JellyfinGradients.HeroHalo], which sits over a backdrop and has
 * to compete with it; this one is the only colour on the screen and would read as a wash at hero
 * strength. Apply to a box anchored to the top of the screen whose height follows its width (the
 * radius is width-derived — see the callers' `GLOW_ASPECT`).
 *
 * A draw modifier through the framework [android.graphics.Paint] rather than a Compose [Brush],
 * for one reason: **dithering**. A 17%-alpha fade across hundreds of dp quantises on an 8-bit
 * surface into concentric per-channel stepping rings — visible as a pixelated texture on a large
 * dark panel (device walk, 2026-08-16) — and `Paint.isDither` is the switch that trades them for
 * imperceptible noise, which Compose's gradient brushes do not expose. The shader is rebuilt only
 * when the size changes ([drawWithCache]); the paint is one allocation per size change, not per
 * frame.
 */
fun Modifier.screenGlow(): Modifier =
    drawWithCache {
        val paint =
            Paint().apply {
                isDither = true
                shader =
                    RadialGradient(
                        size.width * JellyfinGradients.SCREEN_GLOW_CENTER_X_FRACTION,
                        0f,
                        size.width * JellyfinGradients.SCREEN_GLOW_RADIUS_FRACTION,
                        intArrayOf(
                            JellyfinColors.Secondary.copy(alpha = JellyfinGradients.SCREEN_GLOW_ALPHA).toArgb(),
                            android.graphics.Color.TRANSPARENT,
                        ),
                        floatArrayOf(0f, JellyfinGradients.SCREEN_GLOW_FADE_STOP),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
            }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
