package dev.jellyboost.player.gesture

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The claims the curve's KDoc makes: an exact inverse pair (a swipe and the Display slider read the
 * same level back), monotonic ends, and a middle that sits far below the backlight's own middle —
 * which is the whole reason the curve exists.
 */
class BrightnessCurveTest {
    @Test
    fun `the ends are the ends`() {
        BrightnessCurve.toBacklight(0f) shouldBe 0f
        BrightnessCurve.toBacklight(1f) shouldBe (1f plusOrMinus TOLERANCE)
        BrightnessCurve.toFraction(0f) shouldBe 0f
        BrightnessCurve.toFraction(1f) shouldBe (1f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a level survives the round trip`() {
        for (step in 0..STEPS) {
            val fraction = step.toFloat() / STEPS
            BrightnessCurve.toFraction(BrightnessCurve.toBacklight(fraction)) shouldBe
                (fraction plusOrMinus TOLERANCE)
        }
    }

    @Test
    fun `half the travel is a fifth of the backlight`() {
        val half = BrightnessCurve.toBacklight(0.5f)
        half shouldBe (0.1842f plusOrMinus TOLERANCE)
        // The panel reaches its non-HBM maximum near 0.5, so the top quarter is what buys sunlight.
        BrightnessCurve.toBacklight(0.75f) shouldBe (0.4823f plusOrMinus TOLERANCE)
    }

    @Test
    fun `the bottom of the travel is where the fine steps are`() {
        // A linear map put every one of these within a hair of each other; the curve separates them.
        val low = BrightnessCurve.toBacklight(0.05f)
        val lower = BrightnessCurve.toBacklight(0.1f)
        low shouldBe (0.0055f plusOrMinus TOLERANCE)
        lower shouldBe (0.0113f plusOrMinus TOLERANCE)
        lower shouldNotBe low
    }

    @Test
    fun `every step up is a step up`() {
        var previous = -1f
        for (step in 0..STEPS) {
            val backlight = BrightnessCurve.toBacklight(step.toFloat() / STEPS)
            (backlight > previous) shouldBe true
            previous = backlight
        }
    }

    @Test
    fun `values outside the range are clamped, never wrapped`() {
        BrightnessCurve.toBacklight(-0.5f) shouldBe 0f
        BrightnessCurve.toBacklight(2f) shouldBe (1f plusOrMinus TOLERANCE)
        // `BRIGHTNESS_OVERRIDE_NONE`, should it ever reach the conversion.
        BrightnessCurve.toFraction(-1f) shouldBe 0f
        BrightnessCurve.toFraction(2f) shouldBe (1f plusOrMinus TOLERANCE)
    }

    private companion object {
        const val STEPS = 100
        const val TOLERANCE = 0.001f
    }
}
