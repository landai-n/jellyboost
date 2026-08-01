package dev.jellyboost.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.core.ui.component.PillSnackbar
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.LocalHazeState
import kotlinx.coroutines.launch

/**
 * The app's outer frame: the [JellyfinNavHost] with the app's floating chrome drawn *over* it, plus
 * the snackbar that explains the connection status.
 *
 * ### The inset contract (2026 refresh)
 * The frame is a plain `Box`, not a `Scaffold`, and it reserves no space for anything. Every piece
 * of chrome — [GlassBottomNav], [GlassTopNav], [AppActionCluster] — is a *sibling* of the nav host
 * that floats on top of it, so the page below always fills the whole window and its content scrolls
 * under the glass, which is the whole point of the refresh's material (DECISIONS.md 2026-08-01).
 *
 * - **Pushed destinations are unchanged.** Settings, LibraryGrid, ItemDetail, the player and the
 *   auth flow each manage their own system-bar insets and get no chrome at all; nothing here pads
 *   them, exactly as before.
 * - **Top-level destinations consume [LocalAppChromePadding]** in the `contentPadding` of whatever
 *   they scroll. That is the one thing a top-level screen has to do, and it replaces the
 *   `innerPadding`/bottom-inset the old `Scaffold` used to hand down: padding that scrolls away,
 *   rather than a window that got shorter. See the composition local's own KDoc for the values.
 *
 * ### Which chrome a window gets
 * [useBottomNav] decides, from one [BoxWithConstraints] at this level (one subcomposition per
 * screen, the codebase's rule against per-item constraint reads). Below [TopNavMinWidth] the
 * navigation is the floating bottom pill and the app-wide actions float in the top-right corner as
 * the [AppActionCluster]; at and above it, [GlassTopNav] carries both in one row.
 *
 * ### Why the chrome is animated rather than switched
 * `isTopLevel` is read from the back stack, so it flips the instant `navigate()` is called — a good
 * half-second before the [JellyfinNavHost] cross-fade that follows it has finished. Drawing the
 * chrome with a bare `if` therefore made it disappear from under a screen that was still fading.
 * Both the bars and the padding they publish are consequently animated on the same clock the pages
 * use ([NAV_TRANSITION_MILLIS]) — the bars through `AnimatedVisibility`, the padding through
 * [animateDpAsState] so a list's `contentPadding` slides to zero instead of snapping.
 *
 * The chrome is fed the last *top-level* destination rather than the live one, because during the
 * exit animation the current destination is already the pushed screen and the selected-tab pill
 * would blink off halfway through the fade.
 *
 * ### One backdrop, one source
 * [HazeState] is created here and the nav host is the only `hazeSource` in the app: every glass
 * surface in the chrome samples the page underneath it through `LocalHazeState`. A second source —
 * inside a lazy item, say — would sample a node that scrolls, which is both wrong and expensive.
 */
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val isTopLevel = currentDestination.isTopLevel()

    // What the chrome shows while it animates away: the destination it was last drawn for. Writing
    // it during composition is idempotent — the same value for the same destination — and it is only
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

    val hazeState = remember { HazeState() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bottomNav = useBottomNav(maxWidth)
        val chromePadding = chromePadding(isTopLevel = isTopLevel, bottomNav = bottomNav)

        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalAppChromePadding provides chromePadding,
        ) {
            JellyfinNavHost(
                startsSignedIn = startsSignedIn,
                sessionState = sessionState,
                navController = navController,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            )

            AnimatedVisibility(
                visible = isTopLevel,
                enter = fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopChromeScrim(
                    height = topChromeInset + if (bottomNav) ActionClusterHeight else TopNavHeight,
                )
            }

            AnimatedVisibility(
                visible = isTopLevel && !bottomNav,
                enter = slideInVertically { -it } + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = slideOutVertically { -it } + fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                GlassTopNav(
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

            AnimatedVisibility(
                visible = isTopLevel && bottomNav,
                enter = fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                AppActionCluster(
                    connectionState = connectionState,
                    hasActiveSyncPlayGroup = activeSyncPlayGroup != null,
                    onConnectionStatusClick = showConnectionStatus,
                    onOpenSyncPlayGroups = { navController.navigate(Routes.SyncPlay) },
                    onNavigateToSettings = { navController.navigate(Routes.Settings) },
                    onSetForceOffline = connectionViewModel::setForceOffline,
                )
            }

            AnimatedVisibility(
                visible = isTopLevel && bottomNav,
                enter = slideInVertically { it } + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = slideOutVertically { it } + fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = BottomNavMargin),
            ) {
                GlassBottomNav(
                    currentDestination = barDestination,
                    onSelectTab = navController::navigateToTab,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).snackbarInset(chromePadding),
            ) { data ->
                PillSnackbar(snackbarData = data)
            }
        }
    }
}

/**
 * How much faster than the page cross-fade the chrome leaves.
 *
 * The bars are drawn for the whole of [NAV_TRANSITION_MILLIS] so that they do not vanish from under
 * a screen that is still fading (see [AppScaffold]'s KDoc) — but for that whole time the pushed
 * screen underneath is *also* fading in, with its own top-right buttons: the detail screen's overlay
 * nav, the library grid's sort, SyncPlay's create, the player's cast. Two sets of controls in the
 * same corner, both semi-transparent, read as one smeared pile. Halving the chrome's exit clears the
 * corner well before the incoming screen is legible, and the *padding* is deliberately left on the
 * full clock — that one is read by a list's `contentPadding`, where a faster collapse would make the
 * content jump under the fade.
 */
private const val CHROME_EXIT_DIVISOR = 2

/**
 * The band of darkened background the top chrome is read against.
 *
 * A sibling of the nav host, drawn over it and under the bars — deliberately *not* a background on
 * [GlassTopNav]'s row or on the cluster. Those pieces are glass, and glass samples the `hazeSource`
 * around the nav host; a scrim inside that source would be blurred into the very surfaces it is
 * meant to protect, and a scrim inside a `hazeEffect` would be sampling an effect rather than a
 * backdrop, which Haze does not do (`GlassTopNav`'s KDoc). Outside both, it is exactly what it looks
 * like: a gradient over the page, with the glass floating on top of it.
 */
@Composable
private fun TopChromeScrim(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(JellyfinGradients.TopChromeScrim),
    )
}

/**
 * Keeps the snackbar clear of whatever is at the bottom of the window.
 *
 * With the floating nav pill up that is the chrome's own bottom padding — the pill's height, its
 * margin and the navigation-bar inset — so the snackbar floats just above it. Everywhere else the
 * frame consumes no insets at all, so the host has to keep itself off the gesture bar on its own.
 */
private fun Modifier.snackbarInset(chromePadding: PaddingValues): Modifier {
    val bottom = chromePadding.calculateBottomPadding()
    return if (bottom > 0.dp) padding(bottom = bottom) else navigationBarsPadding()
}

/**
 * How much of a top-level screen the chrome covers, as the animated value published through
 * [LocalAppChromePadding] — see that composition local for the contract itself.
 *
 * Both ends animate over [NAV_TRANSITION_MILLIS] rather than switching, for the reason spelled out
 * in [AppScaffold]'s KDoc: the value is read by a list's `contentPadding`, and a screen fading out
 * while its padding snapped to zero jumped visibly under the fade.
 *
 * The compact top value is the status bar plus the floating [AppActionCluster] rather than zero.
 * The cluster overlaps content by design — the mocks' home hero runs full-bleed under it — but a
 * screen's *first, non-scrolling* row (the search field) would otherwise sit permanently under the
 * Cast and overflow buttons, so the frame keeps that band clear and lets the rest scroll under.
 *
 * Both variants add [Dimens.SpaceSmall] of clearance on top of the bar's own height. Reserving
 * *exactly* the bar meant a screen's first row came to rest touching the glass, so any rounding —
 * a shadow, a focus ring, a row whose own top padding was zero — read as an overlap.
 */
@Composable
private fun chromePadding(
    isTopLevel: Boolean,
    bottomNav: Boolean,
): PaddingValues {
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarInset = topChromeInset

    val topTarget =
        when {
            !isTopLevel -> 0.dp
            bottomNav -> statusBarInset + ActionClusterHeight + Dimens.SpaceSmall
            else -> statusBarInset + TopNavHeight + Dimens.SpaceSmall
        }
    val bottomTarget =
        if (isTopLevel && bottomNav) navigationBarInset + BottomNavMargin + BottomNavHeight else 0.dp

    val top by animateDpAsState(
        targetValue = topTarget,
        animationSpec = tween(NAV_TRANSITION_MILLIS),
        label = "chromeTopPadding",
    )
    val bottom by animateDpAsState(
        targetValue = bottomTarget,
        animationSpec = tween(NAV_TRANSITION_MILLIS),
        label = "chromeBottomPadding",
    )

    return remember(top, bottom) { PaddingValues(top = top, bottom = bottom) }
}

/**
 * Builds the action behind the chrome's offline status icon: a snackbar carrying the reason the app
 * is offline, and — for the two reasons the user can act on — the action that fixes it.
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

/**
 * How every tab is switched to: the nav bars' own taps, and the one in-content affordance that
 * crosses tabs — the home screen's *Offline* quick-access chip, which is the Downloads tab.
 */
internal fun NavHostController.navigateToTab(route: Any) {
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
 * makes the later `restoreStateInternal` a no-op. Since the chrome is hidden on pushed destinations
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
