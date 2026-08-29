package dev.jellyboost.core.ui.theme

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
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

object JellyfinGradients {
    val Accent: Brush =
        Brush.horizontalGradient(
            colors = listOf(JellyfinColors.Secondary, JellyfinColors.Primary),
        )

    val AccentDiagonal: Brush =
        Brush.linearGradient(
            colors = listOf(JellyfinColors.Secondary, JellyfinColors.Primary),
        )

    /**
     * The scrim over a hero or a detail backdrop, and the app's statement of the canvas's doctrine
     * (`design/screens/home-light.html`): **image territory is dark-scrimmed in both schemes**, and
     * a light page begins at the artwork's bottom edge rather than being faded into.
     *
     * The dark ramp still ends on the real `colorScheme.background`, because there the page colour
     * *is* the ink — that also keeps a dynamic-colour dark page seamless. The light ramp cannot do
     * the same: a scrim fading to `#EEF1F7` milks the picture out and leaves everything drawn on it
     * with no ground, so the light side rides [OverMedia.ScrimInk] instead and hands the copy on it
     * over-media white rather than scheme ink.
     *
     * Where the page's *own* content is drawn across the artwork's bottom edge there is no edge to
     * begin at — that is [StageScrim], not this.
     */
    val BackdropScrim: Brush
        @Composable @ReadOnlyComposable
        get() =
            if (LocalIsLightTheme.current) {
                LightBackdropScrim
            } else {
                val background = MaterialTheme.colorScheme.background
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            background.copy(alpha = 0.65f),
                            background,
                        ),
                )
            }

    /**
     * [BackdropScrim] for artwork the page overlaps rather than abuts — the wide detail stage, whose
     * poster and facts column are drawn across the backdrop's bottom edge in scheme ink. Scheme ink
     * needs the page's own colour under it, so here the light ramp does fade to the page: the eased
     * one, because white at the dark ramp's alphas fogs a picture where black shades it.
     */
    val StageScrim: Brush
        @Composable @ReadOnlyComposable
        get() {
            val background = MaterialTheme.colorScheme.background
            return if (LocalIsLightTheme.current) {
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to Color.Transparent,
                            BACKDROP_LIGHT_ONSET_STOP to background.copy(alpha = BACKDROP_LIGHT_ONSET_ALPHA),
                            BACKDROP_LIGHT_MID_STOP to background.copy(alpha = BACKDROP_LIGHT_MID_ALPHA),
                            1f to background,
                        ),
                )
            } else {
                Brush.verticalGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            background.copy(alpha = 0.65f),
                            background,
                        ),
                )
            }
        }

    /** The canvas's own ramp: nothing until 42%, then down to 78% at the artwork's foot. */
    private val LightBackdropScrim: Brush =
        Brush.verticalGradient(
            colorStops =
                arrayOf(
                    0f to Color.Transparent,
                    BACKDROP_INK_MID_STOP to OverMedia.ScrimInk.copy(alpha = BACKDROP_INK_MID_ALPHA),
                    1f to OverMedia.ScrimInk.copy(alpha = BACKDROP_INK_FOOT_ALPHA),
                ),
        )

    /**
     * Strong on purpose: section titles scroll behind the brand mark and still read through
     * 80%/45%. Must be drawn as a sibling *over* the page and *under* the bars — never inside a
     * `hazeSource` or a `hazeEffect`, since Haze samples a backdrop rather than another effect.
     */
    val TopChromeScrim: Brush
        @Composable @ReadOnlyComposable
        get() {
            val background = MaterialTheme.colorScheme.background
            return if (LocalIsLightTheme.current) {
                // Same fog-versus-shadow asymmetry as `BackdropScrim`: the band keeps its strength
                // right under the bars, then falls off early instead of hazing a third of the page.
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to background.copy(alpha = TOP_CHROME_LIGHT_TOP_ALPHA),
                            TOP_CHROME_LIGHT_MID_STOP to background.copy(alpha = TOP_CHROME_LIGHT_MID_ALPHA),
                            1f to Color.Transparent,
                        ),
                )
            } else {
                Brush.verticalGradient(
                    colors =
                        listOf(
                            background.copy(alpha = TOP_CHROME_NEAR_ALPHA),
                            background.copy(alpha = TOP_CHROME_MID_ALPHA),
                            Color.Transparent,
                        ),
                )
            }
        }

    /**
     * The wide hero's left-edge wash: near-solid under the copy, transparent before the artwork's
     * focal right half. Lives here, not in the hero, because it takes the same doctrine as
     * [BackdropScrim] — dark ink over the picture in both schemes, and never the page colour on the
     * light side, since the copy it grounds is over-media white either way.
     *
     * The stops are one set, not two: only the colour differed between the schemes, and it no longer
     * does past the light branch's ink.
     */
    val WideHeroScrim: Brush
        @Composable @ReadOnlyComposable
        get() =
            if (LocalIsLightTheme.current) {
                LightWideHeroScrim
            } else {
                val background = MaterialTheme.colorScheme.background
                Brush.horizontalGradient(
                    colorStops =
                        arrayOf(
                            0f to background.copy(alpha = WIDE_HERO_NEAR_ALPHA),
                            WIDE_HERO_MID_STOP to background.copy(alpha = WIDE_HERO_MID_ALPHA),
                            WIDE_HERO_END_STOP to Color.Transparent,
                        ),
                )
            }

    private val LightWideHeroScrim: Brush =
        Brush.horizontalGradient(
            colorStops =
                arrayOf(
                    0f to OverMedia.ScrimInk.copy(alpha = WIDE_HERO_NEAR_ALPHA),
                    WIDE_HERO_MID_STOP to OverMedia.ScrimInk.copy(alpha = WIDE_HERO_MID_ALPHA),
                    WIDE_HERO_END_STOP to Color.Transparent,
                ),
        )

    /**
     * [TopChromeScrim] for the band that lands on a hero rather than on the page: the page-coloured
     * one is a white haze painted over the picture on the light side, which is the reading the canvas
     * rejected. One brush for both schemes, for [BackdropScrim]'s reason — and the frosted-white
     * light chrome drawn on it is then over the darkest-artwork case `ContrastRatioTest` pins it at.
     */
    val OverMediaTopChromeScrim: Brush =
        Brush.verticalGradient(
            colors =
                listOf(
                    OverMedia.ScrimInk.copy(alpha = TOP_CHROME_NEAR_ALPHA),
                    OverMedia.ScrimInk.copy(alpha = TOP_CHROME_MID_ALPHA),
                    Color.Transparent,
                ),
        )

    /**
     * A [ShaderBrush], not `Brush.radialGradient`: the radius must derive from the *measured*
     * height, or the fade is still visible where the box ends and reads as a hard seam across the
     * background (seen on the tablet in landscape, where a width-driven radius dwarfed the height).
     *
     * Two prebuilt instances rather than a brush built per composition: `Modifier.background`
     * compares the brush by identity, so a fresh one per call would re-materialise the node on
     * every recomposition of the auth screen.
     */
    val BrandGlow: Brush
        @Composable @ReadOnlyComposable
        get() = if (LocalIsLightTheme.current) LightBrandGlow else DarkBrandGlow

    /**
     * Fill a full-bleed box with this: the radius is sized to reach zero before any interior edge,
     * so a smaller box cuts the gradient mid-fade and leaves a seam.
     */
    val BrandGlowSide: Brush
        @Composable @ReadOnlyComposable
        get() = if (LocalIsLightTheme.current) LightBrandGlowSide else DarkBrandGlowSide

    private val DarkBrandGlow: Brush = brandGlow(BRAND_GLOW_CENTER_ALPHA, BRAND_GLOW_MID_ALPHA)

    private val LightBrandGlow: Brush = brandGlow(BRAND_GLOW_CENTER_ALPHA_LIGHT, BRAND_GLOW_MID_ALPHA_LIGHT)

    private val DarkBrandGlowSide: Brush = brandGlowSide(BRAND_GLOW_CENTER_ALPHA, BRAND_GLOW_MID_ALPHA)

    private val LightBrandGlowSide: Brush =
        brandGlowSide(BRAND_GLOW_CENTER_ALPHA_LIGHT, BRAND_GLOW_MID_ALPHA_LIGHT)

    internal const val BRAND_GLOW_CENTER_ALPHA = 0.20f
    internal const val BRAND_GLOW_MID_ALPHA = 0.09f

    /**
     * The saved design canvas gives the light page its own pair (`tokens.css`'s
     * `[data-theme="light"] .brand-glow`), and it is *not* the two-fifths [heroHalo] takes: this
     * wash is the auth page's whole decoration with nothing drawn through it, so it keeps almost
     * its full strength and only the blue half rises to hold the cooler ground.
     */
    internal const val BRAND_GLOW_CENTER_ALPHA_LIGHT = 0.16f

    internal const val BRAND_GLOW_MID_ALPHA_LIGHT = 0.10f

    /** The canvas's ramp: transparent, then 30% at 42%, then 78% at the artwork's foot. */
    private const val BACKDROP_INK_MID_STOP = 0.42f

    private const val BACKDROP_INK_MID_ALPHA = 0.30f

    /** What every over-media ink's quoted ratio is measured against. */
    internal const val BACKDROP_INK_FOOT_ALPHA = 0.78f

    /** Nothing but a whisper of page until halfway down the artwork. */
    private const val BACKDROP_LIGHT_ONSET_STOP = 0.50f

    private const val BACKDROP_LIGHT_ONSET_ALPHA = 0.18f

    private const val BACKDROP_LIGHT_MID_STOP = 0.78f

    private const val BACKDROP_LIGHT_MID_ALPHA = 0.62f

    private const val TOP_CHROME_NEAR_ALPHA = 0.94f

    private const val TOP_CHROME_MID_ALPHA = 0.72f

    private const val TOP_CHROME_LIGHT_TOP_ALPHA = 0.92f

    private const val TOP_CHROME_LIGHT_MID_STOP = 0.40f

    private const val TOP_CHROME_LIGHT_MID_ALPHA = 0.50f

    private const val WIDE_HERO_NEAR_ALPHA = 0.94f

    private const val WIDE_HERO_MID_STOP = 0.38f

    /** The wash's weakest point under the wide copy column — where [OverMedia.Meta] is measured. */
    internal const val WIDE_HERO_MID_ALPHA = 0.72f

    private const val WIDE_HERO_END_STOP = 0.70f

    /** Where the purple centre has become the blue mid-stop, as a fraction of the radius. */
    private const val BRAND_GLOW_MID_STOP = 0.45f

    private fun brandGlow(
        centerAlpha: Float,
        midAlpha: Float,
    ): Brush =
        object : ShaderBrush() {
            private val centerYFraction = 0.08f

            override fun createShader(size: Size): Shader {
                val centerY = size.height * centerYFraction
                return RadialGradientShader(
                    center = Offset(x = size.width / 2f, y = centerY),
                    radius = size.height - centerY,
                    colors = brandGlowColors(centerAlpha, midAlpha),
                    colorStops = listOf(0f, BRAND_GLOW_MID_STOP, 1f),
                    tileMode = TileMode.Clamp,
                )
            }
        }

    private fun brandGlowSide(
        centerAlpha: Float,
        midAlpha: Float,
    ): Brush =
        object : ShaderBrush() {
            private val centerXFraction = 0.28f

            private val radiusFraction = 0.55f

            override fun createShader(size: Size): Shader =
                RadialGradientShader(
                    center = Offset(x = size.width * centerXFraction, y = 0f),
                    radius = size.width * radiusFraction,
                    colors = brandGlowColors(centerAlpha, midAlpha),
                    colorStops = listOf(0f, BRAND_GLOW_MID_STOP, 1f),
                    tileMode = TileMode.Clamp,
                )
        }

    private fun brandGlowColors(
        centerAlpha: Float,
        midAlpha: Float,
    ): List<Color> =
        listOf(
            JellyfinColors.Secondary.copy(alpha = centerAlpha),
            JellyfinColors.Primary.copy(alpha = midAlpha),
            Color.Transparent,
        )

    internal const val HERO_HALO_CENTER_X_FRACTION = 0.78f

    internal const val HERO_HALO_CENTER_Y_FRACTION = 0.18f

    /** Fraction of the box *width*. */
    internal const val HERO_HALO_RADIUS_X_FRACTION = 1.2f

    /** Fraction of the box *height* — see [heroHalo] on why the two axes differ. */
    internal const val HERO_HALO_RADIUS_Y_FRACTION = 0.9f

    internal const val HERO_HALO_FADE_STOP = 0.72f

    /**
     * One pair for both schemes, unlike [BRAND_GLOW_CENTER_ALPHA_LIGHT]'s: the halo is drawn on
     * image territory, which is dark-scrimmed whatever the page is, so there is no light ground for
     * it to stain. The dimmed light pair it used to carry was answering a page it never sits on.
     */
    internal const val HERO_HALO_CENTER_ALPHA = 0.35f
    internal const val HERO_HALO_MID_ALPHA = 0.16f
    internal const val HERO_HALO_MID_STOP = 0.42f

    internal const val SCREEN_GLOW_CENTER_X_FRACTION = 0.22f

    internal const val SCREEN_GLOW_RADIUS_FRACTION = 0.8f

    internal const val SCREEN_GLOW_FADE_STOP = 0.76f

    internal const val SCREEN_GLOW_ALPHA = 0.17f

    /** [BRAND_GLOW_CENTER_ALPHA_LIGHT]'s reasoning, applied to the screen glow — this one is on the page. */
    internal const val SCREEN_GLOW_ALPHA_LIGHT = 0.08f

    /** The two card greys of the *active* scheme: an empty poster is a card that has not loaded. */
    val ImagePlaceholder: Brush
        @Composable @ReadOnlyComposable
        get() =
            Brush.linearGradient(
                colors =
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface,
                    ),
            )
}

/**
 * Apply to a box anchored to the top of the screen whose height follows its width — the radius is
 * width-derived (see the callers' `GLOW_ASPECT`).
 *
 * A framework [android.graphics.Paint] rather than a Compose [Brush] for one reason: a 17%-alpha
 * fade across hundreds of dp quantises into visible per-channel stepping rings on an 8-bit surface,
 * and `Paint.isDither` — the switch that trades them for noise — is not exposed by Compose brushes.
 */
@Composable
fun Modifier.screenGlow(): Modifier {
    val alpha =
        if (LocalIsLightTheme.current) {
            JellyfinGradients.SCREEN_GLOW_ALPHA_LIGHT
        } else {
            JellyfinGradients.SCREEN_GLOW_ALPHA
        }
    // Remembered on the alpha the lambda now captures: a fresh lambda per recomposition makes the
    // `drawWithCache` element compare unequal, and its setter throws away the very `Paint` and
    // native shader this factory exists to keep (`GlassDefaults.glassSurface` carries the same rule).
    val onBuildDrawCache: CacheDrawScope.() -> DrawResult =
        remember(alpha) {
            {
                val paint =
                    Paint().apply {
                        isDither = true
                        shader =
                            RadialGradient(
                                size.width * JellyfinGradients.SCREEN_GLOW_CENTER_X_FRACTION,
                                0f,
                                size.width * JellyfinGradients.SCREEN_GLOW_RADIUS_FRACTION,
                                intArrayOf(
                                    JellyfinColors.Secondary.copy(alpha = alpha).toArgb(),
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
        }
    return drawWithCache(onBuildDrawCache)
}

/**
 * Fill the hero backdrop's own box with this.
 *
 * The two radii must stay separately derived — width for x, *height* for y. Collapsing them to the
 * width-derived one made the box's bottom edge cut the gradient mid-fade on a landscape tablet: a
 * hard seam across the page, the same clipped-glow bug as [JellyfinGradients.BrandGlow]. As an
 * ellipse the fade completes at ~83% of the box height; the top and end edges that still clip it
 * are window edges with no page beyond them.
 *
 * Dithered framework [Paint] for [screenGlow]'s reason, and its `remember` for [screenGlow]'s reason —
 * unkeyed, because the halo's strength is the same in both schemes
 * ([JellyfinGradients.HERO_HALO_CENTER_ALPHA]).
 * [RadialGradient] cannot express an ellipse, hence the local matrix scaling the circular shader
 * about its own centre.
 */
@Composable
fun Modifier.heroHalo(): Modifier {
    val onBuildDrawCache: CacheDrawScope.() -> DrawResult =
        remember {
            {
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
                                    JellyfinColors.Secondary
                                        .copy(alpha = JellyfinGradients.HERO_HALO_CENTER_ALPHA)
                                        .toArgb(),
                                    JellyfinColors.Primary
                                        .copy(alpha = JellyfinGradients.HERO_HALO_MID_ALPHA)
                                        .toArgb(),
                                    android.graphics.Color.TRANSPARENT,
                                ),
                                floatArrayOf(
                                    0f,
                                    JellyfinGradients.HERO_HALO_MID_STOP,
                                    JellyfinGradients.HERO_HALO_FADE_STOP,
                                ),
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
        }
    return drawWithCache(onBuildDrawCache)
}
