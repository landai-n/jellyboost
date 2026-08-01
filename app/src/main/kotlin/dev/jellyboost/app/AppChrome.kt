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
import dev.jellyboost.core.ui.theme.Dimens

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

/** Gap between the status bar and the top of the compact action cluster's buttons. */
internal val ActionClusterTopGap: Dp = 8.dp

/**
 * Height of the compact layout's floating action cluster, its gap below the status bar included.
 *
 * *Derived*, not a number: the cluster is one row of glass action buttons under an
 * [ActionClusterTopGap] gap, and each of those buttons lays out at [Dimens.MinTouchTarget] however
 * big the circle it draws inside that frame is (see `JellyfinButtons.kt`). It used to be a literal
 * 44dp, which was neither the 36dp circle nor the 48dp row that actually got laid out, and the
 * 12dp shortfall is what let a screen's first row slide under the Cast and overflow buttons.
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
 * The insets every piece of *top* chrome keeps itself clear of: the status bar, and — the part both
 * bars used to miss — the display cutout.
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
