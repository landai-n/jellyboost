package dev.jellyboost.app

import dev.jellyboost.core.common.Routes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the navigation options behind the app bar's four tabs.
 *
 * The pushed screens' Home affordance used to navigate with these very same options; it now has its
 * own, and the reason is pinned by [HomeNavOptionsTest].
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
}
