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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

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
     * [Modifier.backdropScrim] for artwork the page overlaps rather than abuts — the wide detail
     * stage, whose poster and facts column are drawn across the backdrop's bottom edge in scheme
     * ink. Scheme ink needs the page's own colour under it, so here the light ramp does fade to the
     * page: the eased one, because white at the dark ramp's alphas fogs a picture where black
     * shades it. Nothing over-media is drawn on it, so it has no copy zone to hold and stays a
     * plain fraction ramp.
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

    /**
     * The canvas's foot value, promoted to a **plateau**: it is the ground every over-media ink's
     * quoted ratio is measured against, so the ramp has to be holding it everywhere the copy is
     * drawn — not only at the very bottom, which is where a fraction ramp puts it and where the
     * copy is not. `#101010`@78% over a white backdrop leaves white at 9.67:1, [OverMedia.Eyebrow]
     * at 6.65:1 and [OverMedia.Meta] at 6.19:1; `#0C0E14` is marginally darker, so the dark scheme
     * is the binding case and the one `ContrastRatioTest` pins.
     */
    internal const val BACKDROP_PLATEAU_ALPHA = 0.78f

    /** How far above the copy zone the ramp climbs out of transparent. */
    internal val BackdropRise: Dp = 140.dp

    /**
     * The dark ramp's tail: it alone still has to arrive at an opaque page colour, because in the
     * dark scheme the artwork dissolves into the page rather than ending on an edge, and
     * `HomeHero`'s rail fade and 48dp overlap are seated on that dissolve.
     */
    internal val BackdropDarkFootRun: Dp = 140.dp

    /** Clear of the copy column's right edge before the wash starts letting go. */
    internal val WideHeroWashMargin: Dp = 24.dp

    /** How long the wash takes to reach transparent past [WideHeroWashMargin]. */
    internal val WideHeroWashFade: Dp = 280.dp

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

    internal const val WIDE_HERO_NEAR_ALPHA = 0.94f

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
 * The scrim over a hero or a detail backdrop, and the app's statement of the canvas's doctrine
 * (`design/screens/home-light.html`): **image territory is dark-scrimmed in both schemes**, and a
 * light page begins at the artwork's bottom edge rather than being faded into.
 *
 * A `Modifier` and not a `Brush`, for `heroHalo`'s reason turned on a different axis: a ramp whose
 * stops are *fractions of the banner* protects a fraction, and what needs protecting is a lockup
 * measured in **dp**, which moves up the picture as the font scale grows and the banner does not.
 * The fraction ramp put its full strength at the foot, 100–220dp below where the copy actually sits.
 * So the geometry is anchored to [copyZone] instead: transparent at the top, climbing over
 * [JellyfinGradients.BackdropRise], and holding [JellyfinGradients.BACKDROP_PLATEAU_ALPHA] from the
 * lockup's ceiling all the way down. A banner too short to hold both starts part-way up the climb
 * rather than jumping — which is the case where the copy has filled the picture anyway.
 *
 * The dark ramp keeps going past the plateau to the real `colorScheme.background`, because there the
 * page colour *is* the ink and the artwork dissolves into the page (that also keeps a dynamic-colour
 * dark page seamless). The light ramp cannot: fading to `#EEF1F7` milks the picture out and leaves
 * everything drawn on it with no ground, so it rides [OverMedia.ScrimInk] and holds flat to the foot.
 *
 * Where the page's own content is drawn *across* the artwork's bottom edge there is no edge to begin
 * at and no copy on the picture — that is [JellyfinGradients.StageScrim], not this.
 *
 * @param copyZone how far above the artwork's foot the over-media lockup drawn on it reaches. Pass
 *   the layout's own measurement (`compactHeroCopyZone`, `detailLockupCopyZone`), never a guess:
 *   this is the one number that decides whether the ink has a ground.
 */
@Composable
fun Modifier.backdropScrim(copyZone: Dp): Modifier {
    val light = LocalIsLightTheme.current
    val ink = if (light) OverMedia.ScrimInk else MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val copyZonePx = with(density) { copyZone.toPx() }
    val risePx = with(density) { JellyfinGradients.BackdropRise.toPx() }
    val footRunPx = if (light) 0f else with(density) { JellyfinGradients.BackdropDarkFootRun.toPx() }
    val footAlpha = if (light) JellyfinGradients.BACKDROP_PLATEAU_ALPHA else 1f
    // Remembered on everything the lambda captures — `screenGlow`'s rule.
    val onBuildDrawCache: CacheDrawScope.() -> DrawResult =
        remember(ink, copyZonePx, risePx, footRunPx, footAlpha) {
            {
                val stops =
                    backdropScrimStops(
                        heightPx = size.height,
                        copyZonePx = copyZonePx,
                        risePx = risePx,
                        footRunPx = footRunPx,
                        footAlpha = footAlpha,
                    )
                val brush =
                    Brush.verticalGradient(
                        colorStops = stops.map { (at, alpha) -> at to ink.copy(alpha = alpha) }.toTypedArray(),
                        startY = 0f,
                        endY = size.height,
                    )
                onDrawBehind { drawRect(brush = brush) }
            }
        }
    return drawWithCache(onBuildDrawCache)
}

/**
 * [Modifier.backdropScrim]'s geometry, as a pure function so `BackdropScrimGeometryTest` can pin the
 * one guarantee the whole doctrine rests on: the plateau is already held at the copy zone's ceiling.
 *
 * Fractions are strictly increasing and the last one is always 1, which is what
 * `Brush.verticalGradient` requires of its stops.
 *
 * @param footRunPx `0` where the scrim holds its plateau flat to the foot (light); otherwise the run
 *   over which it deepens to [footAlpha], and also the shortest tail the plateau may leave itself.
 */
internal fun backdropScrimStops(
    heightPx: Float,
    copyZonePx: Float,
    risePx: Float,
    footRunPx: Float,
    footAlpha: Float,
): List<Pair<Float, Float>> {
    val plateau = JellyfinGradients.BACKDROP_PLATEAU_ALPHA
    if (heightPx <= 0f) return listOf(0f to plateau, 1f to footAlpha)
    val ceiling = (heightPx - maxOf(copyZonePx, footRunPx)).coerceIn(0f, heightPx)
    // Where the climb would start if there were room above the ceiling for all of it.
    val onset = ceiling - risePx
    val startAlpha = if (onset >= 0f || risePx <= 0f) 0f else plateau * (-onset / risePx)
    val stops = mutableListOf(0f to startAlpha)
    if (onset > 0f) stops += (onset / heightPx) to 0f
    if (ceiling > 0f && ceiling < heightPx) stops += (ceiling / heightPx) to plateau
    stops += 1f to footAlpha
    return stops.distinctBy { it.first }
}

/**
 * The wide hero's left-edge wash: near-solid under the copy, transparent before the artwork's focal
 * right half. It takes [Modifier.backdropScrim]'s doctrine — dark ink over the picture in both
 * schemes — and its geometry lesson: the copy column is `24dp + 420dp` of **dp**, so a wash whose
 * mid stop was a fraction of the window let the copy's right half slide onto thinning wash on every
 * window narrower than ~1168dp, and the wide layout starts at 600dp.
 *
 * Mirrored under RTL, because the copy column is.
 *
 * @param copyEdge the copy column's far edge, measured from the leading side.
 */
@Composable
fun Modifier.wideHeroWash(copyEdge: Dp): Modifier {
    val light = LocalIsLightTheme.current
    val ink = if (light) OverMedia.ScrimInk else MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val holdPx = with(density) { (copyEdge + JellyfinGradients.WideHeroWashMargin).toPx() }
    val fadePx = with(density) { JellyfinGradients.WideHeroWashFade.toPx() }
    val onBuildDrawCache: CacheDrawScope.() -> DrawResult =
        remember(ink, holdPx, fadePx) {
            {
                val stops = wideHeroWashStops(widthPx = size.width, holdPx = holdPx, fadePx = fadePx)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val brush =
                    Brush.horizontalGradient(
                        colorStops = stops.map { (at, alpha) -> at to ink.copy(alpha = alpha) }.toTypedArray(),
                        startX = if (rtl) size.width else 0f,
                        endX = if (rtl) 0f else size.width,
                    )
                onDrawBehind { drawRect(brush = brush) }
            }
        }
    return drawWithCache(onBuildDrawCache)
}

/** [Modifier.wideHeroWash]'s geometry, pure for the same reason [backdropScrimStops] is. */
internal fun wideHeroWashStops(
    widthPx: Float,
    holdPx: Float,
    fadePx: Float,
): List<Pair<Float, Float>> {
    val near = JellyfinGradients.WIDE_HERO_NEAR_ALPHA
    val plateau = JellyfinGradients.BACKDROP_PLATEAU_ALPHA
    if (widthPx <= 0f || holdPx >= widthPx) return listOf(0f to near, 1f to plateau)
    val stops = mutableListOf(0f to near, (holdPx / widthPx) to plateau)
    val end = holdPx + fadePx
    if (end < widthPx) stops += (end / widthPx) to 0f
    stops += 1f to (if (end < widthPx) 0f else plateau * (1f - (widthPx - holdPx) / fadePx))
    return stops.distinctBy { it.first }
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
