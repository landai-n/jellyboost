package dev.jellyboost.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.model.SessionState
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
 * of the status bar, and the nav host gets the bottom navigation-bar inset — the space the bottom
 * navigation bar used to reserve for it.
 *
 * ### Why the chrome is animated rather than switched
 * `isTopLevel` is read from the back stack, so it flips the instant `navigate()` is called — a good
 * half-second before the [JellyfinNavHost] cross-fade that follows it has finished. Drawing the bar
 * with a bare `if` therefore added or removed a ~112dp slot under a screen that was still fading:
 * `innerPadding` snapped, the outgoing page jumped, and the bar and its page never came or went
 * together. Both ends of the frame are consequently animated on the same clock the pages use
 * ([NAV_TRANSITION_MILLIS]) — the bar through `AnimatedVisibility`, which the `Scaffold` re-measures
 * every frame, and the bottom inset through [animateDpAsState] instead of a toggled modifier. The
 * inset contract above is unchanged; only the way the top-level padding arrives is.
 *
 * The bar is fed the last *top-level* destination rather than the live one, because during the exit
 * animation the current destination is already the pushed screen and the selected-tab pill would
 * blink off halfway through the fade.
 */
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val isTopLevel = currentDestination.isTopLevel()

    // What the bar shows while it animates away: the destination it was last drawn for. Writing it
    // during composition is idempotent — the same value for the same destination — and it is only
    // ever read below, after this line has run.
    var barDestination by remember { mutableStateOf<NavDestination?>(null) }
    if (isTopLevel) barDestination = currentDestination

    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()

    val syncPlayBadgeViewModel: SyncPlayBadgeViewModel = hiltViewModel()
    val activeSyncPlayGroup by syncPlayBadgeViewModel.activeGroup.collectAsStateWithLifecycle()

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

    // The bottom half of the frame, as a value rather than a modifier, so it can be animated to and
    // from zero on the same clock as the bar instead of appearing and vanishing in one frame.
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset by animateDpAsState(
        targetValue = if (isTopLevel) navigationBarInset else 0.dp,
        animationSpec = tween(NAV_TRANSITION_MILLIS),
        label = "navHostBottomPadding",
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Expanding/shrinking from the top (the defaults) is what makes the bar slide out from
            // under the status bar rather than detach from it.
            AnimatedVisibility(
                visible = isTopLevel,
                enter = expandVertically(tween(NAV_TRANSITION_MILLIS)) + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = shrinkVertically(tween(NAV_TRANSITION_MILLIS)) + fadeOut(tween(NAV_TRANSITION_MILLIS)),
            ) {
                AppTopBar(
                    currentDestination = barDestination,
                    connectionState = connectionState,
                    hasActiveSyncPlayGroup = activeSyncPlayGroup != null,
                    onSelectTab = navController::navigateToTab,
                    onConnectionStatusClick = showConnectionStatus,
                    onOpenSyncPlayGroups = { navController.navigate(Routes.SyncPlay) },
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
                    .padding(bottom = bottomInset),
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
            // `actionLabel` alone would default `duration` to Indefinite (M3's `showSnackbar`) — the
            // M9 device walk found the offline snackbar sitting over the last list row for minutes.
            // `Long` still leaves the action tappable, just not forever.
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Long,
                )
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
    navigate(route, topLevelNavOptions())
}

/**
 * The Home affordance every pushed screen carries next to its Back button: one tap out of a chain
 * of any depth, landing on the Home tab.
 *
 * It is a `navigate` rather than `popBackStack(Routes.Home, inclusive = false)`, which is the
 * shorter-looking way to say "pop everything above Home". The difference is what happens when Home
 * is *not* on the back stack. `popBackStack` to an absent destination returns `false` and does
 * nothing at all: the user taps Home and stays exactly where they are, with no feedback — the same
 * silent-no-op failure mode that produced duplicate `HomeViewModel`s before 649a7c8 (see
 * [topLevelNavOptions]). `navigate` cannot fail that way: the `popUpTo` clause may no-op, but
 * `launchSingleTop` then finds no Home to collapse onto and the navigation pushes one. The
 * affordance therefore always does what its icon promises.
 *
 * On both launch shapes Home *is* in fact on the stack whenever a pushed screen is reachable — a
 * signed-in launch starts at Home, and a signed-out launch starts at `Routes.ServerSetup` but
 * reaches the signed-in area only through `navigateClearingBackStack(Routes.Home)`, which leaves
 * Home as the single entry — so the pop path is the one that normally runs, and `launchSingleTop`
 * keeps it from stacking a second Home on top of the one it just uncovered. The `navigate` form is
 * chosen for the case that analysis does not cover: a future deep link, a restored process, or any
 * new entry point into a detail chain that does not pass through Home.
 */
internal fun NavHostController.navigateHome() {
    navigate(Routes.Home, homeNavOptions())
}

/**
 * The options behind [navigateHome]. Identical to [topLevelNavOptions] but for the two state flags,
 * which are deliberately **off** — and that difference is the whole of this function's reason to
 * exist.
 *
 * `navigateHome` originally reused [topLevelNavOptions] verbatim, on the reasoning that the Home
 * button and the Home tab want the same thing. They do not, because `saveState`/`restoreState` are
 * not symmetric around the *pop target*. In `NavControllerImpl.executePopOperations`, a
 * **non-inclusive** `popUpTo(X) { saveState = true }` maps the state it just saved to `X`'s own id:
 *
 * ```
 * if (saveState) {
 *     if (!inclusive) {
 *         generateSequence(foundDestination) { … }
 *             .takeWhile { !backStackMap.containsKey(it.id) }
 *             .forEach { backStackMap[it.id] = savedState.firstOrNull()?.id }
 * ```
 *
 * and `NavControllerImpl.navigate` then reads that map *after* the pop has already run:
 *
 * ```
 * if (navOptions?.shouldRestoreState() == true && backStackMap.containsKey(node.id)) {
 *     navigated = restoreStateInternal(node.id, …)
 * ```
 *
 * With `node == Routes.Home` those two are the same key. Tapping Home on a screen pushed from Home
 * therefore saved the chain under `Home`, then immediately restored it — the destination changed
 * from ItemDetail to Home to ItemDetail within one call, and the button looked completely dead.
 * Device-verified on the test tablet: the *same* LibraryGrid screen obeyed the button when reached
 * via the Libraries tab and ignored it when reached from Home's "See all".
 *
 * The tab bar escapes this only by accident. `popUpTo<Home>` from Home itself pops nothing, so
 * `savedState.firstOrNull()?.id` is `null`, and `backStackMap[Home] = null` acts as a sentinel that
 * makes the later `restoreStateInternal` a no-op. Since the bar is hidden on pushed destinations
 * ([isTopLevel]), every tab switch starts from a top-level screen and that sentinel is always in
 * place — which is why [topLevelNavOptions] is left exactly as it was.
 *
 * Dropping `saveState` here also means a Home tap discards the chain it unwinds, including an
 * intermediate tab entry: Libraries → grid → Home leaves the Libraries tab showing its root next
 * time. That matches what pressing Back the same number of times would do, and it is the price of
 * never writing a Home-keyed entry into `backStackMap` — a single such entry would go on to hijack
 * the *tab* as well, landing the user on a stale detail screen when they tap Home in the bar.
 */
internal fun homeNavOptions(): NavOptions =
    navOptions {
        popUpTo<Routes.Home> { saveState = false }
        launchSingleTop = true
        restoreState = false
    }

/**
 * The options every tab switch navigates with: keep one copy of each tab's back stack, restore it
 * on return, and never pile up duplicate destinations from repeated taps on the same tab.
 *
 * Tab switches only — the pushed screens' Home affordance needs [homeNavOptions] instead, for the
 * reason spelled out there.
 *
 * The pop target is [Routes.Home] — the root of the signed-in area — and deliberately **not**
 * `graph.findStartDestination()`, which is what the standard tabbed-navigation snippet uses. This
 * graph has two possible start destinations: `Routes.Home` on a launch that already has a session,
 * but `Routes.ServerSetup` on one that does not (see [JellyfinNavHost]), and signing in *navigates*
 * to Home rather than rebuilding the graph. On that second launch the graph's start destination is
 * therefore not on the back stack at all, and `popUpTo` an absent destination is a documented no-op
 * ("Ignoring popBackStack to destination … as it was not found on the current back stack"): nothing
 * is popped, nothing is saved for `restoreState` to find, and `launchSingleTop` only collapses a
 * destination that is already on top. Every tab tap then *pushed* a new entry, so returning to Home
 * from another tab created a **second** Home entry — a second `HomeViewModel` with its own
 * `UserDataEventBus` collector, which is why one watched-state change fired two identical
 * `getResumeItems`/`getNextUp` refreshes, and why re-selecting the Home tab reloaded the screen.
 *
 * Internal rather than private so the flags themselves are unit-testable: a `NavController` needs a
 * device, but the options it is handed do not.
 */
internal fun topLevelNavOptions(): NavOptions =
    navOptions {
        popUpTo<Routes.Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
