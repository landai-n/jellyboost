package dev.jellyboost.feature.library.libraries

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [librariesMinCellWidth] — the `GridCells.Adaptive` floor `LibrariesGrid` picks
 * for a given viewport width.
 *
 * A phone-width viewport (360dp, 328dp available after [Dimens.ScreenPadding]) used to collapse
 * to a single full-width column because the grid floor was the tablet-calibrated
 * [Dimens.ThumbWidth] (210dp) at every width. These tests pin the compact branch (150dp, below
 * 600dp) and the tablet branch (unchanged at [Dimens.ThumbWidth], at and above 600dp) so that
 * regression can't sneak back in.
 */
class LibrariesSizingTest {
    @Test
    fun `a 360dp phone viewport uses the compact floor`() {
        librariesMinCellWidth(360.dp) shouldBe 150.dp
    }

    @Test
    fun `just under the compact cutoff still uses the compact floor`() {
        librariesMinCellWidth(599.dp) shouldBe 150.dp
    }

    @Test
    fun `the compact cutoff itself uses the tablet floor`() {
        librariesMinCellWidth(600.dp) shouldBe Dimens.ThumbWidth
    }

    @Test
    fun `the test tablet portrait width uses the tablet floor, unchanged`() {
        librariesMinCellWidth(711.dp) shouldBe Dimens.ThumbWidth
    }
}
