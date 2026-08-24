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

// The sizes live here, not inside the two bar composables, because `AppScaffold` builds
// `LocalAppChromePadding` from them and a screen's first row rests at the edge of the glass only
// while the two agree to the pixel.

/** Below this width four labelled tabs crowd the app actions out, so the chrome moves to the bottom. */
internal val TopNavMinWidth: Dp = 560.dp

/** Above its own margin. */
internal val BottomNavHeight: Dp = 60.dp

/** Left, right, and below the pill (over the navigation bar). */
internal val BottomNavMargin: Dp = 20.dp

/** Above whatever the status bar takes. */
internal val TopNavHeight: Dp = 64.dp

internal val ActionClusterTopGap: Dp = 8.dp

/**
 * Derived, never a literal: an action button lays out at [Dimens.MinTouchTarget] whatever size circle
 * it draws, and a number that drifted from that would let a screen's first row slide under the
 * cluster.
 */
internal val ActionClusterHeight: Dp = ActionClusterTopGap + Dimens.MinTouchTarget

/**
 * Every margin around the chrome's actions is corrected by this: padding applies to the invisible
 * [Dimens.MinTouchTarget] frame, but what the eye lines up is the circle drawn inside it.
 */
internal val ActionFrameOverhang: Dp = (Dimens.MinTouchTarget - Dimens.PillHeightSmall) / 2

internal val ActionClusterEndMargin: Dp = 12.dp

/** That margin as padding on the cluster's frame — see [ActionFrameOverhang]. */
internal val ActionClusterEndPadding: Dp = ActionClusterEndMargin - ActionFrameOverhang

/**
 * `statusBarsPadding()` is only correct while the cutout is inside the status bar — in landscape the
 * notch is a *horizontal* inset, and the brand mark ended up underneath it. Restricted to the top and
 * horizontal sides so it pulls in neither the navigation bar nor the IME, which belong to the screen.
 */
internal val TopChromeInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

internal val topChromeInset: Dp
    @Composable get() = TopChromeInsets.asPaddingValues().calculateTopPadding()

/** A plain function of the measured width, so the breakpoint is unit-testable without a device. */
internal fun useBottomNav(maxWidth: Dp): Boolean = maxWidth < TopNavMinWidth

/** In the order both bars draw them. */
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
 * Spelled out per tab rather than driven from [TopLevelTab.route] because `hasRoute` is reified on
 * the route type, which the enum's value-typed route cannot supply.
 */
internal fun NavDestination?.isSelected(tab: TopLevelTab): Boolean =
    when (tab) {
        TopLevelTab.HOME -> this?.hasRoute<Routes.Home>() == true
        TopLevelTab.LIBRARIES -> this?.hasRoute<Routes.Libraries>() == true
        TopLevelTab.SEARCH -> this?.hasRoute<Routes.Search>() == true
        TopLevelTab.DOWNLOADS -> this?.hasRoute<Routes.Downloads>() == true
    }

/** The chrome is hidden on every destination this is false for. */
internal fun NavDestination?.isTopLevel(): Boolean = TopLevelTab.entries.any { isSelected(it) }

/**
 * A queue must be loaded and the user must not already be looking at it: on [Routes.Player] the bar
 * would offer transport that fights the video controls for the same player, and [Routes.NowPlaying]
 * is its own full-screen view.
 *
 * **Those two exclusions are the whole rule** — [isTopLevel] is deliberately not part of it, or the
 * bar would vanish on the pushed screens playback starts from. Clearance is handled by
 * `AppScaffold.chromePadding`, which folds the bar's height in whatever the destination.
 */
internal fun showsMiniPlayer(
    musicState: MusicPlaybackState,
    onPlayer: Boolean,
    onNowPlaying: Boolean,
): Boolean = musicState is MusicPlaybackState.Active && !onPlayer && !onNowPlaying
