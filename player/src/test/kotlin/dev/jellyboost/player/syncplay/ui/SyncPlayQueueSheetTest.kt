package dev.jellyboost.player.syncplay.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [queueListMaxHeight] — the queue list's cap, which has to follow the sheet's own
 * height on a phone in landscape and leave the fixed tablet cap alone everywhere else.
 */
class SyncPlayQueueSheetTest {
    @Test
    fun `a phone-landscape sheet caps the list at three fifths of itself`() {
        queueListMaxHeight(360.dp) shouldBeDp 216f
    }

    @Test
    fun `the usable height of that sheet leaves room for the header`() {
        // ~330dp is what is left of a 360dp viewport once the sheet's own insets are taken.
        queueListMaxHeight(330.dp) shouldBeDp 198f
    }

    @Test
    fun `a tablet sheet keeps the fixed cap`() {
        queueListMaxHeight(800.dp) shouldBeDp 420f
    }

    @Test
    fun `a very tall sheet still keeps the fixed cap`() {
        queueListMaxHeight(1000.dp) shouldBeDp 420f
    }

    @Test
    fun `an unbounded sheet falls back on the fixed cap`() {
        queueListMaxHeight(Dp.Infinity) shouldBeDp 420f
    }

    /** Dp holds a float, so the fraction's rounding is compared with a tolerance, not exactly. */
    private infix fun Dp.shouldBeDp(expected: Float) = value shouldBe (expected plusOrMinus TOLERANCE)

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
