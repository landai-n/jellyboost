package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The glass language the 2026 refresh uses for every floating surface: navigation chrome, icon
 * buttons, metadata pills, and the panels that sit over artwork (DECISIONS.md 2026-08-01).
 *
 * All of it is white at a very low alpha over the `#101010` background rather than an opaque grey,
 * because the point of the material is that whatever is behind it stays *slightly* visible. The
 * alphas are deliberately small — at these values the fill alone is nearly invisible and the
 * hairline is what actually draws the surface's edge, which is why both are always applied
 * together.
 */
object GlassDefaults {
    /** The surface's own fill, sitting on top of the blurred backdrop (or standing in for it). */
    val Fill: Color = Color.White.copy(alpha = 0.06f)

    /** The 1dp edge that separates a glass surface from the content behind it. */
    val Hairline: Color = Color.White.copy(alpha = 0.09f)

    /** Width of that edge — one device-independent pixel, not one physical one. */
    val HairlineWidth: Dp = 1.dp

    /** Backdrop blur radius, matching the mocks' CSS `backdrop-filter: blur(18px)`. */
    val BlurRadius: Dp = 18.dp

    /** Inner edge drawn *inside* card artwork, lifting the image off a same-coloured background. */
    val ArtworkInnerHairline: Color = Color.White.copy(alpha = 0.07f)

    /** Edge of a form / feedback panel — a touch fainter than [Hairline], as panels are large. */
    val PanelHairline: Color = Color.White.copy(alpha = 0.06f)

    /** Border of a ghost (outlined, unfilled) button, which has no fill to define its shape. */
    val GhostBorder: Color = Color.White.copy(alpha = 0.12f)

    /**
     * The fill of a *chrome* surface — the top nav's tab capsule, the app-wide action circles, the
     * floating bottom pill — as opposed to a card-level one.
     *
     * Chrome floats over whatever the user is looking at, which on this app's screens is very often
     * a bright frame of artwork, and [Fill]'s white@6% over a blurred bright backdrop leaves a white
     * glyph sitting on a near-white surface. A *dark* tint is the only thing that makes the blur
     * subtractive: the backdrop still shows through at 55%, but it is pulled far enough down that
     * white content on top of it keeps its contrast whatever is behind. In-content glass — overlay
     * badges on card artwork, metadata pills — keeps [Fill], because those already sit on artwork
     * the card itself has scrimmed (DECISIONS.md 2026-08-01, chrome readability).
     */
    val ChromeFill: Color = JellyfinColors.Background.copy(alpha = 0.45f)

    /**
     * The floating bottom pill's fill — a step darker again than [ChromeFill].
     *
     * The pill carries the smallest text in the app's chrome (10sp unselected tab labels) and,
     * unlike the top bars, it parks permanently over the brightest part of every screen: Home's
     * full-bleed hero and the poster grids. The top chrome's scrim cannot help it — a scrim behind
     * a glass surface is not part of the sampled backdrop (see `TopChromeScrim`'s KDoc), so the
     * only lever that darkens what the labels sit on is the tint itself. At 45% a bright frame of
     * artwork still composited the labels to roughly 2.5:1; 72% (the mid stop of the top scrim,
     * for coherence) pulls a worst-case backdrop down far enough that full-white labels read.
     */
    val BottomNavFill: Color = JellyfinColors.Background.copy(alpha = 0.72f)

    /**
     * The Haze style every glass surface blurs with.
     *
     * `backgroundColor` is the app background rather than transparent: Haze composites the blurred
     * backdrop over it, and naming the real background is what keeps a surface over an empty
     * region of the screen the same colour as that region instead of washing out towards black.
     */
    fun style(
        tint: Color = Fill,
        blurRadius: Dp = BlurRadius,
    ): HazeStyle =
        HazeStyle(
            backgroundColor = JellyfinColors.Background,
            tint = HazeTint(tint),
            blurRadius = blurRadius,
        )
}

/**
 * The backdrop a glass surface samples, provided by `AppScaffold` around the scrolling content.
 *
 * `null` means "no backdrop source in this composition" — a preview, a test, or a screen drawn
 * outside the scaffold — and [glassSurface] then falls back to a static [GlassDefaults.Fill]. That
 * is also, incidentally, the story below API 31, where real blur is unavailable; Haze handles that
 * case internally by degrading to a scrim, so callers never branch on the SDK level themselves.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Makes [this] a glass surface in [shape]: blurred backdrop where one is available, a flat fill
 * where it is not, and in both cases the hairline that gives the surface its edge.
 *
 * The order of the chain is load-bearing. The clip comes first so the blur is sampled and drawn
 * only inside the shape; the border comes last so the hairline is drawn *over* the fill rather
 * than under it, which at a 9% alpha is the difference between a visible edge and none.
 *
 * A `@Composable` modifier factory rather than `composed {}` (which it originally was, purely to
 * read [LocalHazeState]): `composed` modifiers compare equal to nothing, so every recomposition of
 * a caller re-materialised the whole chain and lazy layouts could never reuse the node — measurable
 * on the hot paths this is applied to (every library tile, the downloads bulk bar, the player
 * controls). The factory reads the composition local at the call site and returns plain node-backed
 * elements (`clip`/`hazeEffect`/`background`/`border`), which diff and reuse normally.
 *
 * @param borderColor the edge to draw. Defaults to [GlassDefaults.Hairline], which is what every
 *   floating surface uses; a ghost *button* is the exception and passes
 *   [GlassDefaults.GhostBorder], because a control the user is meant to press has to read as an
 *   edge rather than as a seam. Stacking a second `border` on top of the default would composite
 *   the two alphas instead of replacing one with the other, hence a parameter.
 * @param tint what the blurred backdrop is composited under (and, where no backdrop is available,
 *   the flat fill that stands in for it). [GlassDefaults.Fill] — white@6% — for anything sitting
 *   inside a screen's own content; chrome that floats over arbitrary artwork passes
 *   [GlassDefaults.ChromeFill] instead, for the reason spelled out there.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    borderColor: Color = GlassDefaults.Hairline,
    tint: Color = GlassDefaults.Fill,
): Modifier {
    val hazeState = LocalHazeState.current
    val backdrop =
        if (hazeState != null) {
            Modifier.hazeEffect(state = hazeState, style = GlassDefaults.style(tint = tint))
        } else {
            Modifier.background(color = tint, shape = shape)
        }
    return clip(shape)
        .then(backdrop)
        .border(width = GlassDefaults.HairlineWidth, color = borderColor, shape = shape)
}

/**
 * The restyled 2026 "m-surface" card fill (spec, "Panels (`m-panel`)"): a solid [surfaceColor] fill,
 * set apart from the page by the same white@6% hairline glass surfaces use rather than by blur —
 * `glassSurface` without the translucency, for cards and stat panels that sit over other cards
 * rather than over a backdrop image.
 *
 * Hoisted from `:feature:downloads`' own private copy (2026 refresh, Phase 5 sweep): SyncPlay's
 * group/queue rows want the identical fill, and a second private copy in `:player` would drift the
 * moment either screen's card language moved half a step.
 *
 * @param radius [Dimens.CardCornerRadius] by default; callers that need a different rounding — a
 *   screen's own spec calls for something else — pass their own.
 */
fun Modifier.mSurface(
    surfaceColor: Color,
    radius: Dp = Dimens.CardCornerRadius,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(color = surfaceColor, shape = shape)
        .border(width = GlassDefaults.HairlineWidth, color = GlassDefaults.PanelHairline, shape = shape)
}
