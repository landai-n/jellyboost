package dev.jellyboost.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Dimens {
    val SpaceExtraSmall: Dp = 4.dp
    val SpaceSmall: Dp = 8.dp
    val SpaceMedium: Dp = 12.dp
    val SpaceLarge: Dp = 16.dp
    val SpaceExtraLarge: Dp = 24.dp

    val ScreenPadding: Dp = 16.dp

    val PosterWidth: Dp = 128.dp

    val ThumbWidth: Dp = 232.dp

    val BackdropHeight: Dp = 220.dp

    val CardCornerRadius: Dp = 12.dp
    val BadgeSize: Dp = 20.dp

    val RadiusXl: Dp = 20.dp

    val PanelRadius: Dp = 16.dp

    val PanelPadding: Dp = 20.dp

    /**
     * Side gutter of a pushed screen's header and of whatever lines up under it. Equal to
     * [PanelPadding] but deliberately its own token: one is a surface's interior, the other a
     * screen's chrome margin, and they move independently.
     */
    val HeaderPadding: Dp = 20.dp

    val PillHeight: Dp = 44.dp

    val PillHeightSmall: Dp = 36.dp

    /**
     * Material's 48dp accessibility minimum. The refresh's buttons draw smaller than this, so each
     * lays out an invisible frame this size around its visual — see `JellyfinButtons.kt`.
     */
    val MinTouchTarget: Dp = 48.dp

    val MPillRadius: Dp = 6.dp

    val OverlayInset: Dp = 10.dp

    val InsetProgressHeight: Dp = 3.dp

    val InsetProgressRadius: Dp = 2.dp

    val LibraryTileWidth: Dp = 232.dp

    val LibraryTileHeight: Dp = 64.dp

    val CastHeadshotSize: Dp = 72.dp

    val DetailPosterWidth: Dp = 190.dp

    /** Pinned rather than derived from the width, so the lockup never reflows by a pixel. */
    val DetailPosterHeight: Dp = 285.dp
}

/** Width / height. */
const val POSTER_ASPECT_RATIO: Float = 2f / 3f

const val THUMB_ASPECT_RATIO: Float = 16f / 9f

const val SQUARE_ASPECT_RATIO: Float = 1f
