package dev.jellyboost.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared spacing and card sizing so every screen lines up on the same rhythm.
 *
 * Card footprints and radii follow the 2026-refresh mocks, which supersede the earlier
 * jellyfin-web-parity sizes (`PosterWidth` 120, `ThumbWidth` 210, `CardCornerRadius` 8) that the M2
 * "reads as the same product side-by-side" definition of done was written against — see
 * DECISIONS.md 2026-08-01, "card metrics and radii leave the jellyfin-web footprint".
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
    val PosterWidth: Dp = 128.dp

    /** Width of a 16:9 thumbnail card. */
    val ThumbWidth: Dp = 232.dp

    /** Height of the backdrop header at the top of a detail screen. */
    val BackdropHeight: Dp = 220.dp

    val CardCornerRadius: Dp = 12.dp
    val BadgeSize: Dp = 20.dp

    /** Extra-large container radius — the biggest rounding the refresh uses (nav pill, sheets). */
    val RadiusXl: Dp = 20.dp

    /** Corner radius of a form / feedback panel (login card, empty and error states). */
    val PanelRadius: Dp = 16.dp

    /** Interior padding of a form / feedback panel, wider than [SpaceLarge] so the panel breathes. */
    val PanelPadding: Dp = 20.dp

    /**
     * Side gutter of a pushed screen's glass header (`ScreenHeader`) — and of whatever lines up
     * under it, which on the library grid is the filter-chip row and the grid itself, so the first
     * poster sits directly below the title.
     *
     * 20dp rather than [ScreenPadding]: the refresh's headers sit a touch wider than the
     * content-only screens.
     *
     * The same 20dp as [PanelPadding], and deliberately a token of its own rather than a reuse of
     * it: one is the *interior* of a surface, the other the *margin* of a screen's chrome, and the
     * two would move independently the moment either did. It lived as a `private val HeaderPadding
     * = 20.dp` in three separate files, kept in step by three prose comments each saying "the same
     * 20dp `LibraryGridScreen`'s header uses" (audit 2026-08-08, DUP-4) — which is a synchronisation
     * mechanism only as long as somebody reads it. Same argument as `GlassDefaults.ChromeFill` and
     * `GlassDefaults.BottomNavFill`, which are equal by reasoning rather than by coincidence.
     */
    val HeaderPadding: Dp = 20.dp

    /** Height of a full-size pill button (primary actions), a comfortable touch target. */
    val PillHeight: Dp = 44.dp

    /** Height of a secondary pill — tab bars, filter chips, and icon buttons in dense chrome. */
    val PillHeightSmall: Dp = 36.dp

    /**
     * The smallest area a control is allowed to *reserve*, whatever it draws inside it — Material's
     * 48dp accessibility minimum.
     *
     * The refresh's buttons are deliberately smaller than this ([PillHeightSmall] circles, [PillHeight]
     * pills), so every one of them lays out an invisible frame this size around its visual and centres
     * the visual in it. Neighbours therefore cannot crowd a button's touch slop, and the drawn surface
     * still comes out at the size the mocks specify — see `JellyfinButtons.kt`.
     */
    val MinTouchTarget: Dp = 48.dp

    /** Corner radius of the mini outlined metadata badge (rating, resolution, codec). */
    val MPillRadius: Dp = 6.dp

    /** Inset of overlay badges and progress bars from the edges of the artwork they sit on. */
    val OverlayInset: Dp = 10.dp

    /** Track height of the resume-progress bar inset into card artwork. */
    val InsetProgressHeight: Dp = 3.dp

    /** Corner radius of that inset progress track, so it reads as a capsule rather than a rule. */
    val InsetProgressRadius: Dp = 2.dp

    /** Width of a library tile on the Libraries screen. */
    val LibraryTileWidth: Dp = 232.dp

    /** Height of a library tile — a wide, short landscape card rather than a square. */
    val LibraryTileHeight: Dp = 64.dp

    /** Diameter of a circular cast-member headshot in the detail screen's cast row. */
    val CastHeadshotSize: Dp = 72.dp

    /** Width of the poster in a detail screen's title lockup. */
    val DetailPosterWidth: Dp = 190.dp

    /** Height of that poster — pinned rather than derived, so the lockup never reflows by a pixel. */
    val DetailPosterHeight: Dp = 285.dp
}

/** Poster artwork aspect ratio (width / height) — the Jellyfin primary image shape. */
const val POSTER_ASPECT_RATIO: Float = 2f / 3f

/** Thumbnail / backdrop artwork aspect ratio (width / height). */
const val THUMB_ASPECT_RATIO: Float = 16f / 9f

/** Square artwork aspect ratio — album and artist art (M13), Jellyfin's music image shape. */
const val SQUARE_ASPECT_RATIO: Float = 1f
