package dev.jellyfinnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jellyfinnative.core.common.Routes
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.feature.auth.LoginScreen
import dev.jellyfinnative.feature.auth.ServerSetupScreen
import dev.jellyfinnative.feature.detail.ItemDetailScreen
import dev.jellyfinnative.feature.downloads.DownloadsScreen
import dev.jellyfinnative.feature.home.HomeScreen
import dev.jellyfinnative.feature.library.LibraryGridScreen
import dev.jellyfinnative.feature.library.libraries.LibrariesScreen
import dev.jellyfinnative.feature.search.SearchScreen
import dev.jellyfinnative.feature.settings.SettingsScreen
import dev.jellyfinnative.player.ui.PlayerScreen

/**
 * The app's navigation graph.
 *
 * `:app` resolves every `@HiltViewModel` via `hiltViewModel()` here and hands it to the feature
 * screen, which keeps the feature modules themselves free of a dependency on `:core:network`'s
 * Hilt component (docs/PLAN.md, "Project skeleton").
 *
 * @param startsSignedIn whether the session existed when the graph was first built.
 * @param sessionState live session; a flip to [SessionState.LoggedOut] from outside the auth
 *   flow (the Settings screen's sign-out today, a 401-driven logout later) pushes the user back to
 *   server setup.
 * @param modifier applied to the [NavHost] itself — [AppScaffold] uses it to reserve space for the
 *   combined app bar and the system navigation bar on the four top-level destinations.
 */
@Composable
internal fun JellyfinNavHost(
    startsSignedIn: Boolean,
    sessionState: SessionState,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    LogoutRedirectEffect(navController = navController, sessionState = sessionState)

    NavHost(
        navController = navController,
        startDestination = if (startsSignedIn) Routes.Home else Routes.ServerSetup,
        modifier = modifier,
    ) {
        composable<Routes.ServerSetup> {
            ServerSetupScreen(
                onNavigateToLogin = { navController.navigate(Routes.Login) },
            )
        }

        composable<Routes.Login> {
            LoginScreen(
                onLoggedIn = { navController.navigateClearingBackStack(Routes.Home) },
                onBackToServerSetup = { navController.popBackStack() },
            )
        }

        composable<Routes.Home> {
            HomeScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigate(Routes.ItemDetail(item.id)) },
                onLibraryClick = { library ->
                    navController.navigate(Routes.LibraryGrid(library.id, library.name))
                },
            )
        }

        composable<Routes.Libraries> {
            LibrariesScreen(
                viewModel = hiltViewModel(),
                onLibraryClick = { library ->
                    navController.navigate(Routes.LibraryGrid(library.id, library.name))
                },
            )
        }

        composable<Routes.Search> {
            SearchScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigate(Routes.ItemDetail(item.id)) },
            )
        }

        composable<Routes.Downloads> {
            DownloadsScreen(viewModel = hiltViewModel())
        }

        composable<Routes.Settings> {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.LibraryGrid> {
            LibraryGridScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigate(Routes.ItemDetail(item.id)) },
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.ItemDetail> {
            ItemDetailScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigate(Routes.ItemDetail(item.id)) },
                onPlay = { itemId, startPositionTicks ->
                    navController.navigate(
                        Routes.Player(itemId = itemId, startPositionTicks = startPositionTicks),
                    )
                },
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.Player> {
            PlayerScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Sends the user back to server setup whenever the session disappears while they are on a
 * signed-in destination.
 *
 * Written against the live session rather than hooked onto the sign-out button so that it also
 * covers logouts the UI did not ask for — a rejected token at M6, for instance. Destinations
 * inside the auth flow are exempt: being logged out there is the normal state of affairs.
 */
@Composable
private fun LogoutRedirectEffect(
    navController: NavHostController,
    sessionState: SessionState,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val destination = currentEntry?.destination
    val inAuthFlow =
        destination?.hasRoute<Routes.ServerSetup>() == true || destination?.hasRoute<Routes.Login>() == true

    LaunchedEffect(sessionState, destination) {
        if (sessionState is SessionState.LoggedOut && destination != null && !inAuthFlow) {
            navController.navigateClearingBackStack(Routes.ServerSetup)
        }
    }
}

/** Navigates to [route] and drops everything behind it — used at both ends of the auth flow. */
private fun NavController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
