package dev.jellyboost.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.ui.theme.Dimens

// The shape of the app's chrome: which of the two navigation layouts a window gets, how tall each
// one is, and the four destinations both of them switch between.
//
// The sizes are here rather than inside the two bar composables because `AppScaffold` needs them to
// build `LocalAppChromePadding`, and a screen's first row coming to rest exactly at the edge of the
// glass depends on the two agreeing to the pixel.

/**
 * Window width at which the chrome moves from the bottom of the screen to the top.
 *
 * This is the 560dp breakpoint below which four labelled tabs would crowd the app actions out —
 * the width at which a single horizontal bar stops being the right shape for this navigation.
 * Below it the chrome is therefore the floating bottom pill ([GlassBottomNav]) with the actions as
 * a small floating cluster in the top-right corner; at and above it, the glass top nav
 * ([GlassTopNav]) carries tabs and actions in one row.
 */
internal val TopNavMinWidth: Dp = 560.dp

/** Height of the floating navigation pill on a compact layout, above its own margin. */
internal val BottomNavHeight: Dp = 60.dp

/** Margin around that pill: to the left, to the right, and below it (over the navigation bar). */
internal val BottomNavMargin: Dp = 20.dp

/** Height of the wide layout's navigation row, above whatever the status bar takes. */
internal val TopNavHeight: Dp = 64.dp

/** Gap between the status bar and the top of the compact action cluster's buttons. */
internal val ActionClusterTopGap: Dp = 8.dp

/**
 * Height of the compact layout's floating action cluster, its gap below the status bar included.
 *
 * *Derived*, not a number: the cluster is one row of glass action buttons under an
 * [ActionClusterTopGap] gap, and each of those buttons lays out at [Dimens.MinTouchTarget] however
 * big the circle it draws inside that frame is (see `JellyfinButtons.kt`). A literal number here
 * risks drifting from the 36dp circle and 48dp row actually laid out, letting a screen's first row
 * slide under the Cast and overflow buttons.
 *
 * The cluster floats over the content rather than reserving space; this is only how much of the top
 * of the window `AppScaffold` keeps clear of a screen's *first* row, so that a static header — the
 * search field, say — never comes to rest underneath it.
 */
internal val ActionClusterHeight: Dp = ActionClusterTopGap + Dimens.MinTouchTarget

/**
 * How far one app action's invisible [Dimens.MinTouchTarget] frame overhangs the circle it actually
 * draws, on each side.
 *
 * The number every margin around the chrome's actions has to be corrected by: padding is applied to
 * the frame, but what the eye lines up is the circle inside it.
 */
internal val ActionFrameOverhang: Dp = (Dimens.MinTouchTarget - Dimens.PillHeightSmall) / 2

/** Distance the compact action cluster's last *circle* keeps from the right edge of the window. */
internal val ActionClusterEndMargin: Dp = 12.dp

/** That margin as padding on the cluster's frame — see [ActionFrameOverhang]. */
internal val ActionClusterEndPadding: Dp = ActionClusterEndMargin - ActionFrameOverhang

/**
 * The insets every piece of *top* chrome keeps itself clear of: the status bar and the display
 * cutout.
 *
 * `statusBarsPadding()` is only correct while the cutout is inside the status bar, which is the
 * portrait case. Rotate a cutout device and the notch becomes a *horizontal* inset: the top nav's
 * brand mark and the cluster's first circle both ended up underneath it. `safeDrawing` restricted to
 * the top and horizontal sides covers both without pulling in the navigation bar or the IME, which
 * belong to whatever the screen itself is doing at the bottom of the window.
 */
internal val TopChromeInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** How far down the window [TopChromeInsets] pushes the chrome — the scrim's and the padding's top. */
internal val topChromeInset: Dp
    @Composable get() = TopChromeInsets.asPaddingValues().calculateTopPadding()

/**
 * Whether a window [maxWidth] wide gets the floating bottom navigation pill rather than the top
 * navigation row — see [TopNavMinWidth] for why the boundary sits where it does.
 *
 * A plain function of the measured width so the breakpoint is unit-testable without a device, the
 * same way `homeThumbCardWidth` and `librariesMinCellWidth` are.
 */
internal fun useBottomNav(maxWidth: Dp): Boolean = maxWidth < TopNavMinWidth

/**
 * The four destinations both navigation layouts switch between, in the order they are drawn.
 *
 * One model for the two bars: the icons, the labels and the routes live in a single place, which
 * is what keeps the phone and tablet chrome from drifting apart.
 */
internal enum class TopLevelTab(
    val route: Any,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    HOME(Routes.Home, Icons.Filled.Home, R.string.nav_home),
    LIBRARIES(Routes.Libraries, Icons.Filled.VideoLibrary, R.string.nav_libraries),
    SEARCH(Routes.Search, Icons.Filled.Search, R.string.nav_search),
    DOWNLOADS(Routes.Downloads, Icons.Filled.Download, R.string.nav_downloads),
}

/**
 * Whether [tab] is the destination currently showing.
 *
 * Spelled out per tab rather than driven from [TopLevelTab.route] because `hasRoute` is reified on
 * the route type: the enum can carry the route as a value for `navigate`, but matching it against a
 * destination needs the type back, and this `when` is where it comes from.
 */
internal fun NavDestination?.isSelected(tab: TopLevelTab): Boolean =
    when (tab) {
        TopLevelTab.HOME -> this?.hasRoute<Routes.Home>() == true
        TopLevelTab.LIBRARIES -> this?.hasRoute<Routes.Libraries>() == true
        TopLevelTab.SEARCH -> this?.hasRoute<Routes.Search>() == true
        TopLevelTab.DOWNLOADS -> this?.hasRoute<Routes.Downloads>() == true
    }

/** The four destinations the chrome can switch between; it is hidden everywhere else. */
internal fun NavDestination?.isTopLevel(): Boolean = TopLevelTab.entries.any { isSelected(it) }

/**
 * Whether [MiniPlayer] belongs on screen right now.
 *
 * A queue must be loaded, and the user must not already be looking at it: [Routes.Player] shows the
 * mini-player would-be duplicate transport for a *video* session mid-handover — the queue survives
 * as a paused snapshot while video borrows the player, and a mini-player for it during that window
 * would invite a tap that fights the video controls for the same player — and [Routes.NowPlaying]
 * is this exact bar's own full-screen view, one tap away.
 *
 * **Those two exclusions are the whole rule** — [isTopLevel] is deliberately not part of it:
 * restricting the bar to the four top-level tabs would hide it on every pushed destination
 * playback actually starts from — an album, an artist, a playlist and the music library — leaving
 * nothing on screen after tapping Play until the user navigates back to a tab. The clearance
 * concern is answered instead by `AppScaffold.chromePadding`, which folds the bar's height in for
 * whatever the destination; the four music screens consume its bottom (see that function's KDoc).
 *
 * A plain function of the two booleans `AppScaffold` already computes for [Routes.Player] rather
 * than of a `NavDestination`, so it is unit-testable without constructing one.
 */
internal fun showsMiniPlayer(
    musicState: MusicPlaybackState,
    onPlayer: Boolean,
    onNowPlaying: Boolean,
): Boolean = musicState is MusicPlaybackState.Active && !onPlayer && !onNowPlaying
