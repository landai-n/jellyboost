package dev.jellyboost.app

import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.navOptions
import dev.jellyboost.core.common.Routes

/*
 * The app's back-stack policy: how a tab is switched to, how the Home affordance gets home, and the
 * two `NavOptions` behind them.
 *
 * It lives here, beside [JellyfinNavHost] — which owns the graph these options navigate *within* —
 * rather than in `AppScaffold.kt`, which merely draws the bars that call them (audit ARCH-8). The
 * saveState/restoreState asymmetry documented on `homeNavOptions` below was found on the test
 * tablet, and it is the kind of thing a reader goes looking for next to the graph, not next to the
 * chrome that happens to draw the buttons.
 */

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
