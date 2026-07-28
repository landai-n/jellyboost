package dev.jellyfinnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/**
 * The app's navigation graph.
 *
 * Three destinations for M1: the two auth screens and a placeholder Home. Everything else in
 * `Routes` arrives with its milestone (docs/PLAN.md, "Screens").
 *
 * @param startsSignedIn whether the session existed when the graph was first built.
 * @param sessionState live session; a flip to [SessionState.LoggedOut] from outside the auth
 *   flow (sign-out today, a 401-driven logout later) pushes the user back to server setup.
 */
@Composable
internal fun JellyfinNavHost(
    startsSignedIn: Boolean,
    sessionState: SessionState,
    onSignOut: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    LogoutRedirectEffect(navController = navController, sessionState = sessionState)

    NavHost(
        navController = navController,
        startDestination = if (startsSignedIn) Routes.Home else Routes.ServerSetup,
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
            HomePlaceholderScreen(
                session = sessionState as? SessionState.LoggedIn,
                onSignOut = onSignOut,
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
