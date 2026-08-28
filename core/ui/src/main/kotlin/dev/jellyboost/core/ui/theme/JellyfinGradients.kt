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
     * Transparent at the top, solid at the bottom: it seats a hero *into* the page below it. It
     * therefore has to end on whatever the page actually is — a scrim fading to `#101010` over a
     * light page is a hard seam where the artwork stops, not a transition.
     */
    val BackdropScrim: Brush
        @Composable @ReadOnlyComposable
        get() {
            val background = MaterialTheme.colorScheme.background
            return Brush.verticalGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        background.copy(alpha = 0.65f),
                        background,
                    ),
            )
        }

    /**
     * Strong on purpose: section titles scroll behind the brand mark and still read through
     * 80%/45%. Must be drawn as a sibling *over* the page and *under* the bars — never inside a
     * `hazeSource` or a `hazeEffect`, since Haze samples a backdrop rather than another effect.
     */
    val TopChromeScrim: Brush
        @Composable @ReadOnlyComposable
        get() {
            val background = MaterialTheme.colorScheme.background
            return Brush.verticalGradient(
                colors =
                    listOf(
                        background.copy(alpha = 0.94f),
                        background.copy(alpha = 0.72f),
                        Color.Transparent,
                    ),
            )
        }

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

    internal const val HERO_HALO_CENTER_ALPHA = 0.35f
    internal const val HERO_HALO_MID_ALPHA = 0.16f
    internal const val HERO_HALO_MID_STOP = 0.42f

    /**
     * The brand *hues* are the same in both schemes — this is the accent gradient's own purple and
     * blue — but the alphas are not: 35% of `#AA5CC3` over `#101010` is a glow, and over `#F6F7F8`
     * the identical wash reads as a stain on the page. Roughly two-fifths is what keeps it a tint.
     */
    internal const val HERO_HALO_CENTER_ALPHA_LIGHT = 0.14f

    internal const val HERO_HALO_MID_ALPHA_LIGHT = 0.07f

    internal const val SCREEN_GLOW_CENTER_X_FRACTION = 0.22f

    internal const val SCREEN_GLOW_RADIUS_FRACTION = 0.8f

    internal const val SCREEN_GLOW_FADE_STOP = 0.76f

    internal const val SCREEN_GLOW_ALPHA = 0.17f

    /** [HERO_HALO_CENTER_ALPHA_LIGHT]'s reasoning, applied to the screen glow. */
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
 * Dithered framework [Paint] for [screenGlow]'s reason, and its `remember` for [screenGlow]'s reason.
 * [RadialGradient] cannot express an ellipse, hence the local matrix scaling the circular shader
 * about its own centre.
 */
@Composable
fun Modifier.heroHalo(): Modifier {
    val light = LocalIsLightTheme.current
    val centerAlpha =
        if (light) JellyfinGradients.HERO_HALO_CENTER_ALPHA_LIGHT else JellyfinGradients.HERO_HALO_CENTER_ALPHA
    val midAlpha =
        if (light) JellyfinGradients.HERO_HALO_MID_ALPHA_LIGHT else JellyfinGradients.HERO_HALO_MID_ALPHA
    val onBuildDrawCache: CacheDrawScope.() -> DrawResult =
        remember(centerAlpha, midAlpha) {
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
                                    JellyfinColors.Secondary.copy(alpha = centerAlpha).toArgb(),
                                    JellyfinColors.Primary.copy(alpha = midAlpha).toArgb(),
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
