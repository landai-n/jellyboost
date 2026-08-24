package dev.jellyboost.feature.library.libraries

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Without a compact floor a 360dp phone collapses to one full-width column, because the floor is
 * the tablet-calibrated [Dimens.ThumbWidth] at every width.
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
