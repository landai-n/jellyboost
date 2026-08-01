package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
 */
fun Modifier.glassSurface(shape: Shape): Modifier =
    composed {
        val hazeState = LocalHazeState.current
        val backdrop =
            if (hazeState != null) {
                Modifier.hazeEffect(state = hazeState, style = GlassDefaults.style())
            } else {
                Modifier.background(color = GlassDefaults.Fill, shape = shape)
            }
        clip(shape)
            .then(backdrop)
            .border(width = GlassDefaults.HairlineWidth, color = GlassDefaults.Hairline, shape = shape)
    }
