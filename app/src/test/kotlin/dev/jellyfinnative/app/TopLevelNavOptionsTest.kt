package dev.jellyfinnative.app

import dev.jellyfinnative.core.common.Routes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the navigation options behind the app bar's four tabs — and behind the Home
 * affordance every pushed screen carries next to its Back button, which navigates with the very
 * same options (`AppScaffold.navigateHome`).
 *
 * Driving a real `NavController` needs a device, but the options it is handed are a plain value, and
 * they are the whole of the tab-switching contract. The pop target is the part worth pinning: with
 * anything that is not on the back stack — `graph.findStartDestination()` on a launch that began
 * logged out, for instance — `popUpTo` is silently ignored, and every tab tap then stacks a *second*
 * copy of the destination with a second ViewModel behind it.
 */
class TopLevelNavOptionsTest {
    @Test
    @DisplayName("tab switches pop up to Home, the root of the signed-in area")
    fun popsUpToHome() {
        topLevelNavOptions().popUpToRouteClass shouldBe Routes.Home::class
    }

    @Test
    @DisplayName("Home itself is kept, so re-selecting a tab never stacks a second copy")
    fun keepsHomeOnTheBackStack() {
        val options = topLevelNavOptions()

        options.isPopUpToInclusive() shouldBe false
        options.shouldLaunchSingleTop() shouldBe true
    }

    @Test
    @DisplayName("each tab's own back stack is saved on the way out and restored on the way back")
    fun savesAndRestoresEachTabsBackStack() {
        val options = topLevelNavOptions()

        options.shouldPopUpToSaveState() shouldBe true
        options.shouldRestoreState() shouldBe true
    }

    @Test
    @DisplayName("the Home affordance pops an arbitrarily deep chain in one tap, not one entry")
    fun homeAffordancePopsTheWholeChain() {
        val options = topLevelNavOptions()

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
        topLevelNavOptions().shouldLaunchSingleTop() shouldBe true
    }
}
