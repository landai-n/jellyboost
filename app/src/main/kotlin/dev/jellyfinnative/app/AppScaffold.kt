package dev.jellyfinnative.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

/**
 * The app's outer frame: the [JellyfinNavHost] under one [AppTopBar], plus the snackbar that
 * explains the connection status.
 *
 * The bar carries the four top-level destinations, the app overflow menu (Settings + the
 * offline-mode toggle) and the offline status icon — it is the whole of the app's chrome on a
 * top-level destination, replacing the bottom `NavigationBar` + per-screen `TopAppBar` + full-width
 * `OfflineBanner` arrangement the app carried until M9 (DECISIONS.md 2026-07-29).
 *
 * `contentWindowInsets = WindowInsets(0)` is deliberate and unchanged: pushed destinations
 * (Settings, LibraryGrid, ItemDetail, the auth flow) each manage their own system-bar insets, and
 * letting this outer `Scaffold` consume them as well would pad those screens twice. What the frame
 * does own on a **top-level** destination is both ends of the window: [AppTopBar] pads itself out
 * of the status bar, and the nav host gets [navigationBarsPadding] — the space the bottom
 * navigation bar used to reserve for it.
 */
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val isTopLevel = currentDestination.isTopLevel()

    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val showConnectionStatus =
        rememberConnectionStatusExplainer(
            state = connectionState,
            snackbarHostState = snackbarHostState,
            onRetry = connectionViewModel::refresh,
            onLeaveOfflineMode = { connectionViewModel.setForceOffline(false) },
        )

    // Coming back to the app is the other moment the plan wants a reachability probe: the network
    // may well have changed while we were not listening (docs/PLAN.md, "Connectivity").
    LifecycleResumeEffect(Unit) {
        connectionViewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isTopLevel) {
                AppTopBar(
                    currentDestination = currentDestination,
                    connectionState = connectionState,
                    onSelectTab = navController::navigateToTab,
                    onConnectionStatusClick = showConnectionStatus,
                    onNavigateToSettings = { navController.navigate(Routes.Settings) },
                    onSetForceOffline = connectionViewModel::setForceOffline,
                )
            }
        },
        snackbarHost = {
            // The frame consumes no insets, so the host has to keep itself off the gesture bar.
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.navigationBarsPadding())
        },
    ) { innerPadding ->
        JellyfinNavHost(
            startsSignedIn = startsSignedIn,
            sessionState = sessionState,
            navController = navController,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .then(if (isTopLevel) Modifier.navigationBarsPadding() else Modifier),
        )
    }
}

/**
 * Builds the action behind the app bar's offline status icon: a snackbar carrying the reason the
 * app is offline, and — for the two reasons the user can act on — the action that fixes it.
 *
 * The strings are resolved here rather than inside the click handler because a handler runs outside
 * composition, where `stringResource` is not available.
 */
@Composable
private fun rememberConnectionStatusExplainer(
    state: ConnectionState,
    snackbarHostState: SnackbarHostState,
    onRetry: () -> Unit,
    onLeaveOfflineMode: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val status = state.toStatus()
    val message = stringResource((status ?: ConnectionStatus.NO_NETWORK).messageRes)
    val actionLabel = status?.actionLabelRes?.let { stringResource(it) }

    return {
        val action =
            when (status) {
                ConnectionStatus.SERVER_UNREACHABLE -> onRetry
                ConnectionStatus.FORCED -> onLeaveOfflineMode
                else -> null
            }
        scope.launch {
            val result = snackbarHostState.showSnackbar(message = message, actionLabel = actionLabel)
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }
}

/** The four destinations the bar can switch between; hidden everywhere else. */
private fun NavDestination?.isTopLevel(): Boolean =
    this?.hasRoute<Routes.Home>() == true ||
        this?.hasRoute<Routes.Libraries>() == true ||
        this?.hasRoute<Routes.Search>() == true ||
        this?.hasRoute<Routes.Downloads>() == true

private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        // Standard tabbed-navigation pattern: keep one copy of each tab's back stack, restore it on
        // return, and never pile up duplicate destinations from repeated taps on the same tab.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
