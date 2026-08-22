package dev.jellyboost.core.ui.theme

import android.graphics.Matrix
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

    /** Horizontal centre of [heroHalo], as a fraction of the box width. */
    internal const val HERO_HALO_CENTER_X_FRACTION = 0.78f

    /** Vertical centre of [heroHalo] — high in the box, so the glow reads as coming from above. */
    internal const val HERO_HALO_CENTER_Y_FRACTION = 0.18f

    /** [heroHalo]'s horizontal radius, as a fraction of the box width (the mock's `120%`). */
    internal const val HERO_HALO_RADIUS_X_FRACTION = 1.2f

    /** [heroHalo]'s vertical radius, as a fraction of the box *height* (the mock's `90%`). */
    internal const val HERO_HALO_RADIUS_Y_FRACTION = 0.9f

    /** Where [heroHalo]'s fade reaches full transparency, as a fraction of its radius. */
    internal const val HERO_HALO_FADE_STOP = 0.72f

    /** [heroHalo]'s two colour stops: the secondary's alpha at the centre, the primary's at 42%. */
    internal const val HERO_HALO_CENTER_ALPHA = 0.35f
    internal const val HERO_HALO_MID_ALPHA = 0.16f
    internal const val HERO_HALO_MID_STOP = 0.42f

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
 * and further to the *start* than [heroHalo], which sits over a backdrop and has
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

/**
 * The 2026 refresh's hero halo: a much stronger, off-centre accent glow than
 * [JellyfinGradients.BrandGlow], laid over a hero backdrop so the top-right of the screen carries
 * colour even where the artwork is dark or missing. Fill the backdrop's own box with it (the detail
 * and home heroes both do).
 *
 * Ports the mocks'
 * `radial-gradient(120% 90% at 78% 18%, rgba(170,92,195,.35) 0%, rgba(0,164,220,.16) 42%,
 * transparent 72%)` — **as the ellipse it is**. The first port collapsed the two axes to the
 * width-derived one, and on a landscape tablet that radius dwarfs the hero box's height, so the box's
 * bottom edge cut the gradient mid-fade: a hard seam across the page where the backdrop ends —
 * the same clipped-glow bug fixed for [JellyfinGradients.BrandGlow] and (as the tablet seam,
 * `c3153a93`) for [screenGlow]'s callers, recurring here as their missed sibling (device walk,
 * 2026-08-22). With the vertical radius height-derived as the mock wrote it, the fade completes at
 * ~83% of the box height and the bottom edge has nothing left to cut. The two edges that do still
 * clip it — top and end — are window edges, where there is no page beyond them to show a seam.
 *
 * A dithered framework [Paint] rather than a Compose [ShaderBrush] for [screenGlow]'s reason: a
 * low-alpha fade across hundreds of dp quantises into visible stepping rings on an 8-bit surface,
 * and `isDither` is the switch Compose's brushes do not expose. The ellipse itself is the one
 * geometry [RadialGradient] cannot express directly, so the circular shader is scaled vertically
 * with a local matrix about its own centre.
 */
fun Modifier.heroHalo(): Modifier =
    drawWithCache {
        val centerX = size.width * JellyfinGradients.HERO_HALO_CENTER_X_FRACTION
        val centerY = size.height * JellyfinGradients.HERO_HALO_CENTER_Y_FRACTION
        val radiusX = size.width * JellyfinGradients.HERO_HALO_RADIUS_X_FRACTION
        val radiusY = size.height * JellyfinGradients.HERO_HALO_RADIUS_Y_FRACTION
        val paint =
            Paint().apply {
                isDither = true
                shader =
                    RadialGradient(
                        centerX,
                        centerY,
                        radiusX,
                        intArrayOf(
                            JellyfinColors.Secondary.copy(alpha = JellyfinGradients.HERO_HALO_CENTER_ALPHA).toArgb(),
                            JellyfinColors.Primary.copy(alpha = JellyfinGradients.HERO_HALO_MID_ALPHA).toArgb(),
                            android.graphics.Color.TRANSPARENT,
                        ),
                        floatArrayOf(0f, JellyfinGradients.HERO_HALO_MID_STOP, JellyfinGradients.HERO_HALO_FADE_STOP),
                        android.graphics.Shader.TileMode.CLAMP,
                    ).apply {
                        setLocalMatrix(
                            Matrix().apply { setScale(1f, radiusY / radiusX, centerX, centerY) },
                        )
                    }
            }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
