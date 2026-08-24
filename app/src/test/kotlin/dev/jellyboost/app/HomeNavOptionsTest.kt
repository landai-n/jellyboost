package dev.jellyboost.app

import dev.jellyboost.core.common.Routes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** These four flags are the whole of the Home affordance's contract: one wrong one breaks the button. */
class HomeNavOptionsTest {
    @Test
    @DisplayName("the Home affordance pops an arbitrarily deep chain in one tap, not one entry")
    fun homeAffordancePopsTheWholeChain() {
        val options = homeNavOptions()

        // Inclusive would pop Home too and re-push it with a fresh `HomeViewModel`; any other pop
        // target would leave part of the chain standing.
        options.popUpToRouteClass shouldBe Routes.Home::class
        options.isPopUpToInclusive() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance re-enters an already-open Home rather than stacking a second")
    fun homeAffordanceNeverStacksASecondHome() {
        // The pop leaves Home on top, so without this the `navigate` pushes a duplicate — two
        // `HomeViewModel`s and two refreshes per watched-state change.
        homeNavOptions().shouldLaunchSingleTop() shouldBe true
    }

    @Test
    @DisplayName("the Home affordance never saves the chain it unwinds under Home's own key")
    fun homeAffordanceDoesNotSaveState() {
        // A non-inclusive `popUpTo(X) { saveState = true }` keys the saved state by X's own id —
        // here Home, the destination being navigated to — so one tap both saved and restored it.
        homeNavOptions().shouldPopUpToSaveState() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance never restores a stack under Home, which is the whole bug")
    fun homeAffordanceDoesNotRestoreState() {
        // `navigate` consults `backStackMap[node.id]` *after* the pop, so with both flags on the
        // button restored the chain it had just popped and appeared dead.
        homeNavOptions().shouldRestoreState() shouldBe false
    }

    @Test
    @DisplayName("the Home affordance is no longer the tab-switch options, and must not become them")
    fun homeAffordanceIsNotTheTabSwitchContract() {
        // Tab switches want both flags and get away with it because `popUpTo<Home>` from a
        // top-level screen writes a null sentinel under Home. Pushed screens have no such protection.
        val tab = topLevelNavOptions()
        val home = homeNavOptions()

        tab.shouldPopUpToSaveState() shouldBe true
        tab.shouldRestoreState() shouldBe true
        home.shouldPopUpToSaveState() shouldBe false
        home.shouldRestoreState() shouldBe false
    }
}
