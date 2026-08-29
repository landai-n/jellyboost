package dev.jellyboost.player.ui

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.jellyboost.core.common.model.SubtitleBackground
import dev.jellyboost.core.common.model.SubtitleTextSize
import kotlin.math.roundToInt

/**
 * Puts the two subtitle-appearance preferences onto Media3's own `SubtitleView`.
 *
 * The two are applied through **separate** Media3 calls and are therefore independent: size can follow the
 * device while the background is overridden, or the other way round. That is why this is two `when`s and
 * not one style object.
 *
 * `SYSTEM` restores `PlayerView`'s untouched behaviour rather than approximating it — `setUserDefaultStyle`
 * and `setUserDefaultTextSize` read Android's `CaptioningManager`, which a user with a caption preference
 * has already set and which this must not silently override.
 *
 * Nothing here reaches libass: with *Styled ASS subtitles* on, an ASS/SSA cue is parsed by
 * `AssNoOpSubtitleParser` and drawn by `AssSubtitleView` from the script's own styles, so `SubtitleView`
 * has no cue of that track to draw and these settings have nothing to act on. Every other format, and
 * ASS/SSA with the switch off, is drawn here.
 */
@UnstableApi
internal fun SubtitleView.applyAppearance(
    textSize: SubtitleTextSize,
    background: SubtitleBackground,
) {
    when (val fraction = textSize.heightFraction) {
        null -> setUserDefaultTextSize()
        else -> setFractionalTextSize(fraction)
    }

    when (val alpha = background.backgroundAlpha) {
        null -> setUserDefaultStyle()
        else -> setStyle(captionStyle(alpha, background.outlined))
    }
}

/**
 * White on black, the caption convention every platform default lands on, with the box's opacity taken
 * from the preference.
 *
 * `windowColor` stays transparent: it fills the whole cue rectangle rather than hugging the glyphs, and a
 * second opaque layer behind the first would make *Translucent* look solid.
 *
 * The edge is a real outline rather than a shadow because it is doing legibility work, not decoration —
 * see [SubtitleBackground.outlined].
 */
@UnstableApi
private fun captionStyle(
    backgroundAlpha: Float,
    outlined: Boolean,
): CaptionStyleCompat =
    CaptionStyleCompat(
        // foregroundColor =
        Color.WHITE,
        // backgroundColor =
        blackWithAlpha(backgroundAlpha),
        // windowColor =
        Color.TRANSPARENT,
        // edgeType =
        if (outlined) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
        // edgeColor =
        Color.BLACK,
        // typeface =
        null,
    )

@ColorInt
private fun blackWithAlpha(alpha: Float): Int = Color.argb((alpha.coerceIn(0f, 1f) * MAX_ALPHA).roundToInt(), 0, 0, 0)

private const val MAX_ALPHA = 255f
