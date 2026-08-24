package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

object GlassDefaults {
    val Fill: Color = Color.White.copy(alpha = 0.06f)

    val Hairline: Color = Color.White.copy(alpha = 0.09f)

    val HairlineWidth: Dp = 1.dp

    val BlurRadius: Dp = 18.dp

    val ArtworkInnerHairline: Color = Color.White.copy(alpha = 0.07f)

    val PanelHairline: Color = Color.White.copy(alpha = 0.06f)

    /**
     * A ghost button's whole visual boundary, so WCAG 1.4.11 asks 3:1 of it: on `#101010` white
     * needs α ≥ 0.333 (0.12 measured 1.38:1). 0.40 is 3.82:1, and 3.75:1 for ghost pills on
     * `#202020`. The other hairlines are seams on surfaces that already have a fill, hence lower.
     */
    val GhostBorder: Color = Color.White.copy(alpha = 0.40f)

    /**
     * Chrome floats over arbitrary artwork, so its tint must be *dark* enough to be subtractive:
     * at 45% a white frame composites to rgb(147) and a white label is 3.05:1, under the 4.5:1 body
     * text owes; at 72% it composites to rgb(83), 7.70:1 white and 4.77:1 for `onSurfaceVariant`.
     * In-content glass keeps [Fill] — it sits on artwork the card already scrimmed.
     */
    val ChromeFill: Color = JellyfinColors.Background.copy(alpha = 0.72f)

    /**
     * Same value and same arithmetic as [ChromeFill], kept separate: a scrim behind a glass surface
     * is not part of the sampled backdrop, so if the top bars ever gain a real scrim the bottom
     * pill still cannot have one and this tint stays its only contrast lever.
     */
    val BottomNavFill: Color = JellyfinColors.Background.copy(alpha = 0.72f)

    /**
     * Downscaled blur is visible as texture on the physical panel even when screenshot analysis
     * measures it clean (`Fixed(0.5f)`: high-pass p99 ≈ 4/255, still visible). Judge any re-attempt
     * on the panel, not on a screenshot diff.
     */
    val DefaultInputScale: HazeInputScale = HazeInputScale.None

    /**
     * `backgroundColor` must name the real app background, not transparent: Haze composites the
     * blurred backdrop over it, and transparent washes a surface over empty screen towards black.
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
 * `null` means no backdrop source in this composition (preview, test, screen outside the scaffold);
 * [glassSurface] then falls back to a flat fill. Haze degrades to a scrim below API 31 by itself,
 * so callers never branch on the SDK level.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Chain order is load-bearing: clip first so the blur is sampled only inside the shape, border last
 * so the hairline draws *over* the fill — at 9% alpha that is the difference between an edge and
 * none.
 *
 * A `@Composable` factory rather than `composed {}`: composed modifiers compare equal to nothing,
 * so every caller recomposition would re-materialise the chain and lazy layouts could never reuse
 * the node — this is applied to every library tile and the player controls.
 *
 * @param borderColor pass a different edge rather than stacking a second `border`, which would
 *   composite the two alphas instead of replacing one.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    borderColor: Color = GlassDefaults.Hairline,
    tint: Color = GlassDefaults.Fill,
    inputScale: HazeInputScale = GlassDefaults.DefaultInputScale,
): Modifier {
    val hazeState = LocalHazeState.current
    // Remembered on inputScale: a fresh lambda per recomposition makes the hazeEffect element
    // compare unequal every time and defeats the node reuse this factory exists for.
    val effect: HazeEffectScope.() -> Unit =
        remember(inputScale) {
            { this.inputScale = inputScale }
        }
    val backdrop =
        if (hazeState != null) {
            Modifier.hazeEffect(state = hazeState, style = GlassDefaults.style(tint = tint), block = effect)
        } else {
            Modifier.background(color = tint, shape = shape)
        }
    return clip(shape)
        .then(backdrop)
        .border(width = GlassDefaults.HairlineWidth, color = borderColor, shape = shape)
}

/** [glassSurface] without the blur, for cards and panels that sit over other cards. */
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
