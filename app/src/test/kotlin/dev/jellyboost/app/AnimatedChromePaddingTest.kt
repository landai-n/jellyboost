package dev.jellyboost.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the two deferred-read [PaddingValues] the scaffold publishes:
 * [AnimatedChromePadding] (what `LocalAppChromePadding` carries) and [SnackbarInset] (what keeps
 * the snackbar off the bottom chrome).
 *
 * The whole point of both classes is *when* the animated value is read: identity stays stable
 * across recompositions while `calculate*` follows the underlying state, so a navigation
 * transition invalidates the layouts that consume the padding rather than the scaffold's whole
 * composition. These tests pin the value side of that contract — the reads track the states, and
 * the horizontal sides stay zero, exactly as the `PaddingValues(top, bottom)` it replaced.
 */
class AnimatedChromePaddingTest {
    @Test
    @DisplayName("top and bottom follow the animated states they wrap")
    fun topAndBottomFollowTheStates() {
        val top = mutableStateOf(80.dp)
        val bottom = mutableStateOf(104.dp)
        val padding = AnimatedChromePadding(top = top, bottom = bottom)

        padding.calculateTopPadding() shouldBe 80.dp
        padding.calculateBottomPadding() shouldBe 104.dp

        top.value = 0.dp
        bottom.value = 12.dp

        padding.calculateTopPadding() shouldBe 0.dp
        padding.calculateBottomPadding() shouldBe 12.dp
    }

    @Test
    @DisplayName("the chrome never pads horizontally")
    fun horizontalSidesAreZero() {
        val padding = AnimatedChromePadding(top = mutableStateOf(80.dp), bottom = mutableStateOf(104.dp))

        padding.calculateLeftPadding(LayoutDirection.Ltr) shouldBe 0.dp
        padding.calculateRightPadding(LayoutDirection.Ltr) shouldBe 0.dp
        padding.calculateLeftPadding(LayoutDirection.Rtl) shouldBe 0.dp
        padding.calculateRightPadding(LayoutDirection.Rtl) shouldBe 0.dp
    }

    @Test
    @DisplayName("the snackbar sits above the chrome when the pill is up")
    fun snackbarUsesTheChromeBottomWhenLarger() {
        val chrome = PaddingValues(bottom = 104.dp)

        SnackbarInset(chrome, navigationBarInset = 24.dp).calculateBottomPadding() shouldBe 104.dp
    }

    @Test
    @DisplayName("the snackbar never dips under the gesture bar while the chrome animates away")
    fun snackbarKeepsTheNavigationBarInsetAsAFloor() {
        // Mid-transition the chrome's bottom passes through values below the inset; the old
        // composition-time branch let the snackbar follow it down and then jump back up.
        SnackbarInset(PaddingValues(bottom = 9.dp), navigationBarInset = 24.dp)
            .calculateBottomPadding() shouldBe 24.dp
        SnackbarInset(PaddingValues(bottom = 0.dp), navigationBarInset = 24.dp)
            .calculateBottomPadding() shouldBe 24.dp
    }

    @Test
    @DisplayName("the snackbar inset is bottom-only")
    fun snackbarInsetIsBottomOnly() {
        val inset = SnackbarInset(PaddingValues(bottom = 104.dp), navigationBarInset = 24.dp)

        inset.calculateTopPadding() shouldBe 0.dp
        inset.calculateLeftPadding(LayoutDirection.Ltr) shouldBe 0.dp
        inset.calculateRightPadding(LayoutDirection.Rtl) shouldBe 0.dp
    }
}
