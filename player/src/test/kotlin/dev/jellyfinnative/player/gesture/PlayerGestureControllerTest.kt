package dev.jellyfinnative.player.gesture

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlayerGestureController].
 *
 * These are the parts of the gesture layer that have a right answer: which half of the screen a
 * swipe belongs to, which third a double tap lands in, how far a finger has to travel for a full
 * sweep, and which edges belong to the system. Everything they leave out — `AudioManager`, the
 * window's brightness attribute — is untestable off a device and is deliberately kept in the
 * composable instead.
 */
class PlayerGestureControllerTest {
    private val controller =
        PlayerGestureController(
            GestureConfig(verticalExclusionPx = EXCLUSION, horizontalExclusionPx = EXCLUSION),
        )

    // ---- swipe zones ---------------------------------------------------------------------------

    @Test
    fun `the left half controls brightness and the right half volume`() {
        controller.swipeTargetFor(xPx = 400f, yPx = MIDDLE_Y, widthPx = WIDTH, heightPx = HEIGHT) shouldBe
            SwipeTarget.BRIGHTNESS
        controller.swipeTargetFor(xPx = 1600f, yPx = MIDDLE_Y, widthPx = WIDTH, heightPx = HEIGHT) shouldBe
            SwipeTarget.VOLUME
    }

    @Test
    fun `a swipe starting in the system's edge strips is left to the system`() {
        // The back-gesture zone on either side...
        controller.swipeTargetFor(20f, MIDDLE_Y, WIDTH, HEIGHT).shouldBeNull()
        controller.swipeTargetFor(WIDTH - 20f, MIDDLE_Y, WIDTH, HEIGHT).shouldBeNull()
        // ...and the status-bar / navigation-bar pull zones.
        controller.swipeTargetFor(400f, 10f, WIDTH, HEIGHT).shouldBeNull()
        controller.swipeTargetFor(400f, HEIGHT - 10f, WIDTH, HEIGHT).shouldBeNull()
    }

    @Test
    fun `a screen with no size yet claims nothing`() {
        controller.swipeTargetFor(0f, 0f, widthPx = 0f, heightPx = 0f).shouldBeNull()
    }

    // ---- swipe distance ------------------------------------------------------------------------

    @Test
    fun `swiping up increases and swiping down decreases`() {
        // 100 px of a 1200 px screen, where a full sweep is 792 px: about an eighth of the range.
        controller.deltaFor(dragPx = -100f, heightPx = HEIGHT) shouldBe (0.1263f plusOrMinus TOLERANCE)
        controller.deltaFor(dragPx = 100f, heightPx = HEIGHT) shouldBe (-0.1263f plusOrMinus TOLERANCE)
    }

    @Test
    fun `two thirds of the screen is a full sweep`() {
        // The whole 0..1 range in one comfortable thumb movement, as jellyfin-android has it.
        controller.deltaFor(dragPx = -(HEIGHT * 0.66f), heightPx = HEIGHT) shouldBe (1f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a zero-height surface produces no change instead of infinity`() {
        controller.deltaFor(dragPx = -100f, heightPx = 0f) shouldBe 0f
    }

    // ---- double tap ----------------------------------------------------------------------------

    @Test
    fun `double tapping the outer thirds seeks by the same amounts as the buttons`() {
        controller.doubleTapSeekMs(xPx = 100f, widthPx = WIDTH) shouldBe -10_000L
        controller.doubleTapSeekMs(xPx = WIDTH - 100f, widthPx = WIDTH) shouldBe 30_000L
    }

    @Test
    fun `the middle third is a dead band, not a seek`() {
        // A double tap in the centre is a fumbled play or pause; seeking there would be a surprise.
        controller.doubleTapSeekMs(xPx = WIDTH / 2f, widthPx = WIDTH).shouldBeNull()
    }

    private companion object {
        const val WIDTH = 2000f
        const val HEIGHT = 1200f
        const val MIDDLE_Y = 600f
        const val EXCLUSION = 100f
        const val TOLERANCE = 0.001f
    }
}
