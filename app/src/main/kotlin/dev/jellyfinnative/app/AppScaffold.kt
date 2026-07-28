package dev.jellyfinnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jellyfinnative.core.common.Routes
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.core.ui.component.OfflineBanner

/**
 * The app's outer frame: the [JellyfinNavHost], a Material 3 bottom navigation bar for the four
 * top-level destinations, and the single app-wide [OfflineBanner] (docs/PLAN.md, "Confirmed
 * decisions" — "bottom nav bar Home / Libraries / Search / Downloads"). The Downloads tab landed
 * with the pipeline behind it at M7, closing DECISIONS.md 2026-07-28 "Downloads tab deferred to M7".
 *
 * The banner sits **above the navigation bar** rather than at the top of the screen. Every screen
 * already draws (and insets) its own `TopAppBar`, so a top-anchored banner would either hide under
 * the status bar or push a second status-bar padding down onto the screen below it; the bottom slot
 * has no such interaction and keeps the notice visible on every destination, bar or no bar.
 *
 * `contentWindowInsets = WindowInsets(0)` is deliberate: every screen already manages its own
 * status-bar insets (`AuthScreenScaffold.safeDrawingPadding()`, the per-screen `Scaffold`s in
 * `:feature:home`/`:feature:library`/`:feature:detail`). Letting this outer `Scaffold` also
 * consume system-bar insets would pad those screens twice; this one only reserves space for what
 * it actually draws.
 */
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
    onSignOut: () -> Unit,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination

    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()

    // Coming back to the app is the other moment the plan wants a reachability probe: the network
    // may well have changed while we were not listening (docs/PLAN.md, "Connectivity").
    LifecycleResumeEffect(Unit) {
        connectionViewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                ConnectionBanner(
                    state = connectionState,
                    onRetry = connectionViewModel::refresh,
                    onLeaveOfflineMode = { connectionViewModel.setForceOffline(false) },
                )
                if (currentDestination.isTopLevel()) {
                    AppNavigationBar(currentDestination = currentDestination, navController = navController)
                }
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

/**
 * The offline notice, with copy and an action chosen by *why* the app is offline — the three
 * reasons call for three different things from the user.
 *
 * @param onRetry re-probes the server; only offered when there is something to retry.
 * @param onLeaveOfflineMode turns the force-offline preference back off.
 */
@Composable
private fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    onLeaveOfflineMode: () -> Unit,
) {
    val message =
        when (state) {
            ConnectionState.ONLINE -> null
            ConnectionState.OFFLINE_NO_NETWORK -> R.string.offline_no_network
            ConnectionState.OFFLINE_SERVER_UNREACHABLE -> R.string.offline_server_unreachable
            ConnectionState.OFFLINE_FORCED -> R.string.offline_forced
        }

    OfflineBanner(
        visible = message != null,
        // Held over during the collapse animation so the text does not blank out mid-transition.
        message = stringResource(message ?: R.string.offline_no_network),
        actionLabel =
            when (state) {
                ConnectionState.OFFLINE_SERVER_UNREACHABLE -> stringResource(R.string.offline_retry)
                ConnectionState.OFFLINE_FORCED -> stringResource(R.string.offline_go_online)
                else -> null
            },
        onAction =
            when (state) {
                ConnectionState.OFFLINE_SERVER_UNREACHABLE -> onRetry
                ConnectionState.OFFLINE_FORCED -> onLeaveOfflineMode
                else -> null
            },
    )
}

/** The four destinations the bar can switch between; hidden everywhere else. */
private fun NavDestination?.isTopLevel(): Boolean =
    this?.hasRoute<Routes.Home>() == true ||
        this?.hasRoute<Routes.Libraries>() == true ||
        this?.hasRoute<Routes.Search>() == true ||
        this?.hasRoute<Routes.Downloads>() == true

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
        AppTab(
            selected = currentDestination?.hasRoute<Routes.Downloads>() == true,
            icon = Icons.Filled.Download,
            label = stringResource(R.string.nav_downloads),
            onClick = { navController.navigateToTab(Routes.Downloads) },
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
