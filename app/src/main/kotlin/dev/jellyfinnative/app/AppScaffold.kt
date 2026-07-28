package dev.jellyfinnative.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jellyfinnative.core.common.Routes
import dev.jellyfinnative.core.network.model.SessionState

/**
 * The app's outer frame: the [JellyfinNavHost] plus a Material 3 bottom navigation bar for the
 * three top-level destinations (docs/PLAN.md, "Confirmed decisions" — "bottom nav bar Home /
 * Libraries / Search / Downloads"; the Downloads tab is deferred to M7, see DECISIONS.md
 * 2026-07-28 "Downloads tab deferred to M7").
 *
 * The offline banner (also an `AppScaffold` responsibility per the plan) arrives with M6's
 * connectivity monitor.
 *
 * `contentWindowInsets = WindowInsets(0)` is deliberate: every screen already manages its own
 * status-bar insets (`AuthScreenScaffold.safeDrawingPadding()`, the per-screen `Scaffold`s in
 * `:feature:home`/`:feature:library`/`:feature:detail`). Letting this outer `Scaffold` also
 * consume system-bar insets would pad those screens twice; this one only reserves space for the
 * bottom bar it actually draws — zero when it is hidden, its measured height when it is shown.
 */
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
    onSignOut: () -> Unit,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentDestination.isTopLevel()) {
                AppNavigationBar(currentDestination = currentDestination, navController = navController)
            }
        },
    ) { innerPadding ->
        JellyfinNavHost(
            startsSignedIn = startsSignedIn,
            sessionState = sessionState,
            onSignOut = onSignOut,
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** The three destinations the bar can switch between; hidden everywhere else. */
private fun NavDestination?.isTopLevel(): Boolean =
    this?.hasRoute<Routes.Home>() == true ||
        this?.hasRoute<Routes.Libraries>() == true ||
        this?.hasRoute<Routes.Search>() == true

@Composable
private fun AppNavigationBar(
    currentDestination: NavDestination?,
    navController: NavHostController,
) {
    NavigationBar {
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Home>() == true,
            icon = Icons.Filled.Home,
            label = stringResource(R.string.nav_home),
            onClick = { navController.navigateToTab(Routes.Home) },
        )
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Libraries>() == true,
            icon = Icons.Filled.VideoLibrary,
            label = stringResource(R.string.nav_libraries),
            onClick = { navController.navigateToTab(Routes.Libraries) },
        )
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Search>() == true,
            icon = Icons.Filled.Search,
            label = stringResource(R.string.nav_search),
            onClick = { navController.navigateToTab(Routes.Search) },
        )
    }
}

private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        // Standard bottom-nav pattern: keep one copy of each tab's back stack, restore it on
        // return, and never pile up duplicate destinations from repeated taps on the same tab.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * An extension on [RowScope] because Material 3's `NavigationBarItem` is one too — it can only be
 * called from inside [NavigationBar]'s content lambda.
 */
@Composable
private fun RowScope.AppTab(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        label = { Text(text = label) },
    )
}
