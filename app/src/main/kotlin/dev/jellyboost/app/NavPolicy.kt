package dev.jellyboost.app

import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.navOptions
import dev.jellyboost.core.common.Routes

internal fun NavHostController.navigateToTab(route: Any) {
    navigate(route, topLevelNavOptions())
}

/**
 * A `navigate`, not `popBackStack(Routes.Home, inclusive = false)`: popping to a destination that is
 * not on the stack is a silent no-op, so the button would do nothing at all on any entry point that
 * did not pass through Home. Here the `popUpTo` may no-op and `launchSingleTop` then pushes a Home.
 */
internal fun NavHostController.navigateHome() {
    navigate(Routes.Home, homeNavOptions())
}

/**
 * [topLevelNavOptions] with the two state flags deliberately **off**, which is this function's whole
 * reason to exist: a non-inclusive `popUpTo(X) { saveState = true }` keys the saved state by `X`'s
 * own id, and navigating to that same `X` with `restoreState` then restores what the pop just saved
 * — Home → ItemDetail → Home within one call, so the button looks dead. The tab bar escapes it only
 * because `popUpTo<Home>` from Home pops nothing, leaving a null sentinel in `backStackMap`.
 *
 * Never write a Home-keyed `backStackMap` entry here: it would hijack the Home *tab* too, landing
 * the user on a stale detail screen. The price is that a Home tap discards the chain it unwinds.
 */
internal fun homeNavOptions(): NavOptions =
    navOptions {
        popUpTo<Routes.Home> { saveState = false }
        launchSingleTop = true
        restoreState = false
    }

/**
 * Tab switches only — a pushed screen's Home affordance needs [homeNavOptions] instead.
 *
 * The pop target is [Routes.Home], deliberately **not** `graph.findStartDestination()`: this graph
 * starts at `Routes.ServerSetup` on a signed-out launch and signing in *navigates* to Home rather
 * than rebuilding the graph, so on that launch the start destination is not on the back stack at
 * all. `popUpTo` an absent destination is a no-op, and every tab tap then pushed a second Home entry
 * — a second `HomeViewModel`, whose duplicate event-bus collector refreshed the screen twice.
 *
 * Internal rather than private so the flags are unit-testable without a `NavController`.
 */
internal fun topLevelNavOptions(): NavOptions =
    navOptions {
        popUpTo<Routes.Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
