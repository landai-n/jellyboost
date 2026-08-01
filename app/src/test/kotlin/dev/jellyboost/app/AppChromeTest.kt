package dev.jellyboost.app

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [useBottomNav] — which of the app's two navigation layouts a window of a given
 * width gets.
 *
 * The breakpoint is the same 560dp the combined app bar used to decide whether its four tabs could
 * afford labels, and it is the one number the whole frame hangs off: it picks the bar, and through
 * it the shape of `LocalAppChromePadding`. Pinning it here keeps it a plain function of the measured
 * width — testable without a device, the same way `homeThumbCardWidth` and `librariesMinCellWidth`
 * are.
 */
class AppChromeTest {
    @Test
    @DisplayName("a phone-width window gets the floating bottom pill")
    fun phoneWidthUsesTheBottomNav() {
        useBottomNav(360.dp) shouldBe true
    }

    @Test
    @DisplayName("just under the breakpoint still gets the bottom pill")
    fun justUnderTheBreakpointUsesTheBottomNav() {
        useBottomNav(559.dp) shouldBe true
    }

    @Test
    @DisplayName("the breakpoint itself switches to the top nav")
    fun theBreakpointItselfUsesTheTopNav() {
        useBottomNav(TopNavMinWidth) shouldBe false
        useBottomNav(560.dp) shouldBe false
    }

    @Test
    @DisplayName("the test tablet portrait width uses the top nav")
    fun tabletPortraitUsesTheTopNav() {
        useBottomNav(711.dp) shouldBe false
    }
}
