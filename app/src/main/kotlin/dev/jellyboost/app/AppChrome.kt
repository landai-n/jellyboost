package dev.jellyboost.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.jellyboost.core.common.Routes

// The shape of the app's chrome: which of the two navigation layouts a window gets, how tall each
// one is, and the four destinations both of them switch between (DECISIONS.md 2026-08-01, the
// 2026-refresh chrome).
//
// The sizes are here rather than inside the two bar composables because `AppScaffold` needs them to
// build `LocalAppChromePadding`, and a screen's first row coming to rest exactly at the edge of the
// glass depends on the two agreeing to the pixel.

/**
 * Window width at which the chrome moves from the bottom of the screen to the top.
 *
 * This is the very same 560dp breakpoint the combined app bar used to decide whether its four tabs
 * could afford labels: below it the four labels crowded the app actions out, which is exactly the
 * width at which a single horizontal bar stops being the right shape for this navigation. Below it
 * the chrome is therefore the floating bottom pill ([GlassBottomNav]) with the actions as a small
 * floating cluster in the top-right corner; at and above it, the glass top nav ([GlassTopNav])
 * carries tabs and actions in one row, the way the old bar did.
 */
internal val TopNavMinWidth: Dp = 560.dp

/** Height of the floating navigation pill on a compact layout, above its own margin. */
internal val BottomNavHeight: Dp = 60.dp

/** Margin around that pill: to the left, to the right, and below it (over the navigation bar). */
internal val BottomNavMargin: Dp = 20.dp

/** Height of the wide layout's navigation row, above whatever the status bar takes. */
internal val TopNavHeight: Dp = 64.dp

/**
 * Height of the compact layout's floating action cluster, its gap below the status bar included.
 *
 * The cluster is one row of 36dp glass buttons under an [ActionClusterTopGap] gap. It floats over
 * the content rather than reserving space; this is only how much of the top of the window
 * `AppScaffold` keeps clear of a screen's *first* row, so that a static header — the search field,
 * say — never comes to rest underneath it.
 */
internal val ActionClusterHeight: Dp = 44.dp

/** Gap between the status bar and the top of the compact action cluster's buttons. */
internal val ActionClusterTopGap: Dp = 8.dp

/** Distance the compact action cluster keeps from the right edge of the window. */
internal val ActionClusterEndPadding: Dp = 12.dp

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
 * One model for the two bars: the icons, the labels and the routes are the ones the combined app
 * bar carried, and having them in a single place is what keeps the phone and tablet chrome from
 * drifting apart as the refresh lands screen by screen.
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
