package dev.jellyboost.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared spacing and card sizing so every screen lines up on the same rhythm.
 *
 * Card widths match jellyfin-web's home rows closely enough that a side-by-side comparison reads
 * as the same product (the M2 definition of done).
 */
object Dimens {
    val SpaceExtraSmall: Dp = 4.dp
    val SpaceSmall: Dp = 8.dp
    val SpaceMedium: Dp = 12.dp
    val SpaceLarge: Dp = 16.dp
    val SpaceExtraLarge: Dp = 24.dp

    /** Horizontal padding applied to screen content and to the start of every media row. */
    val ScreenPadding: Dp = 16.dp

    /** Width of a 2:3 poster card; height follows from the aspect ratio. */
    val PosterWidth: Dp = 120.dp

    /** Width of a 16:9 thumbnail card. */
    val ThumbWidth: Dp = 210.dp

    /** Height of the backdrop header at the top of a detail screen. */
    val BackdropHeight: Dp = 220.dp

    val CardCornerRadius: Dp = 8.dp
    val BadgeSize: Dp = 20.dp
}

/** Poster artwork aspect ratio (width / height) — the Jellyfin primary image shape. */
const val POSTER_ASPECT_RATIO: Float = 2f / 3f

/** Thumbnail / backdrop artwork aspect ratio (width / height). */
const val THUMB_ASPECT_RATIO: Float = 16f / 9f
