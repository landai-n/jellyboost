package dev.jellyboost.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The point of this [PaddingValues] is *when* the animated value is read: a stable identity whose
 * `calculate*` follows the state, so a transition invalidates the consuming layouts rather than the
 * whole scaffold. Pinned here is the value side — reads track the states, horizontal sides stay zero.
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
}
