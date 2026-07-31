package dev.jellyboost.feature.home

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [homeThumbCardWidth] — the fixed width `HomeRows` picks for its thumb-shaped
 * cards (*My Media*, *Continue Watching*, *Next Up*) at a given viewport width.
 *
 * A phone-width viewport (360dp, 328dp available after [Dimens.ScreenPadding]) used to fit only
 * ~1.6 of the tablet-calibrated [Dimens.ThumbWidth] (210dp) cards per row, reading as zoomed-in.
 * These tests pin the compact branch (160dp, below 600dp — sized so two full cards plus a peek of
 * a third fit) and the tablet branch (unchanged at [Dimens.ThumbWidth], at and above 600dp) so
 * that regression can't sneak back in.
 */
class HomeSizingTest {
    @Test
    fun `a 360dp phone viewport uses the compact width`() {
        homeThumbCardWidth(360.dp) shouldBe 160.dp
    }

    @Test
    fun `just under the compact cutoff still uses the compact width`() {
        homeThumbCardWidth(599.dp) shouldBe 160.dp
    }

    @Test
    fun `the compact cutoff itself uses the tablet width`() {
        homeThumbCardWidth(600.dp) shouldBe Dimens.ThumbWidth
    }

    @Test
    fun `the test tablet portrait width uses the tablet width, unchanged`() {
        homeThumbCardWidth(711.dp) shouldBe Dimens.ThumbWidth
    }
}
