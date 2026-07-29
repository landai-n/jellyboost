package dev.jellyfinnative.app

import dev.jellyfinnative.core.common.Routes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the options behind the Home affordance every pushed screen carries next to its
 * Back button (`AppScaffold.navigateHome`).
 *
 * Driving a real `NavController` needs a device, but these options are a plain value — and they are
 * the whole of the affordance's contract, because what broke the button on its first outing was one
 * flag, not one line of UI.
 */
class HomeNavOptionsTest {
    @Test
    @DisplayName("the Home affordance pops an arbitrarily deep chain in one tap, not one entry")
    fun homeAffordancePopsTheWholeChain() {
        val options = homeNavOptions()

        // A detail chain (series → season → episode → similar → …) stacks entirely *above* Home,
        // so a non-inclusive `popUpTo<Home>` unwinds all of it regardless of depth. Were this
        // inclusive, Home would be popped too and re-pushed as a fresh entry with a fresh
        // `HomeViewModel`; were the pop target anything else, part of the chain would survive.
        options.popUpToRouteClass shouldBe Routes.Home::class
        options.isPopUpToInclusive() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance re-enters an already-open Home rather than stacking a second")
    fun homeAffordanceNeverStacksASecondHome() {
        // The chain is popped down *to* Home, which leaves Home on top — at which point the
        // `navigate` in `navigateHome` would push a duplicate without this flag. That duplicate is
        // exactly the 649a7c8 bug: two `HomeViewModel`s, two `UserDataEventBus` collectors, two
        // refreshes per watched-state change.
        homeNavOptions().shouldLaunchSingleTop() shouldBe true
    }

    @Test
    @DisplayName("the Home affordance never saves the chain it unwinds under Home's own key")
    fun homeAffordanceDoesNotSaveState() {
        // `NavControllerImpl.executePopOperations` maps the state saved by a *non-inclusive*
        // `popUpTo(X) { saveState = true }` to X's own destination id — here that is Home, the
        // destination being navigated to. Leaving this on is what let one tap both save and restore
        // the same chain.
        homeNavOptions().shouldPopUpToSaveState() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance never restores a stack under Home, which is the whole bug")
    fun homeAffordanceDoesNotRestoreState() {
        // `NavControllerImpl.navigate` consults `backStackMap[node.id]` *after* running the
        // `popUpTo`, so with both flags on, tapping Home on a screen pushed from Home restored the
        // chain the same call had just popped and the button appeared dead. Home is never itself
        // popped by these options, so it can never have a legitimate saved stack to restore.
        homeNavOptions().shouldRestoreState() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance is no longer the tab-switch options, and must not become them")
    fun homeAffordanceIsNotTheTabSwitchContract() {
        // Tab switches genuinely want both flags — each tab keeps its own stack. They get away with
        // it because `popUpTo<Home>` from a top-level screen writes a null sentinel under Home
        // rather than a real state id. Pushed screens have no such protection.
        val tab = topLevelNavOptions()
        val home = homeNavOptions()

        tab.shouldPopUpToSaveState() shouldBe true
        tab.shouldRestoreState() shouldBe true
        home.shouldPopUpToSaveState() shouldBe false
        home.shouldRestoreState() shouldBe false
    }
}
