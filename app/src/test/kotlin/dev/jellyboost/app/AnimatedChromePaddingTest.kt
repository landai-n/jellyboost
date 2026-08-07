package dev.jellyboost.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the deferred-read [PaddingValues] the scaffold publishes through
 * `LocalAppChromePadding`.
 *
 * The whole point of the class is *when* the animated value is read: identity stays stable across
 * recompositions while `calculate*` follows the underlying state, so a navigation transition
 * invalidates the layouts that consume the padding rather than the scaffold's whole composition.
 * These tests pin the value side of that contract — the reads track the states, and the horizontal
 * sides stay zero, exactly as the `PaddingValues(top, bottom)` it replaced.
 *
 * The snackbar's own inset moved to `:core:ui` with the host that owns it (audit DUP-3); its rules
 * are pinned by `JellyboostSnackbarHostTest`, which keeps every assertion that used to live here
 * and adds the cases the other four hand-written hosts needed.
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
