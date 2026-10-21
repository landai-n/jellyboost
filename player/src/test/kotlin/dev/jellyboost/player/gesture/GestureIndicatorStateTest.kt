package dev.jellyboost.player.gesture

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GestureIndicatorStateTest {
    @Test
    fun `a fresh state shows no indicator`() {
        val state = GestureIndicatorState()

        state.indicator.shouldBeNull()
        state.revision shouldBe 0
    }

    @Test
    fun `showing an identical value twice still bumps the revision twice`() {
        val state = GestureIndicatorState()

        state.show(SwipeTarget.VOLUME, 1f)
        state.show(SwipeTarget.VOLUME, 1f)

        state.indicator shouldBe GestureIndicator(SwipeTarget.VOLUME, 1f)
        state.revision shouldBe 2
    }

    @Test
    fun `hide clears the indicator and a later show sets it again and bumps the revision`() {
        val state = GestureIndicatorState()
        state.show(SwipeTarget.BRIGHTNESS, 0.5f)

        state.hide(state.revision)
        state.indicator.shouldBeNull()

        state.show(SwipeTarget.BRIGHTNESS, 0.5f)
        state.indicator shouldBe GestureIndicator(SwipeTarget.BRIGHTNESS, 0.5f)
        state.revision shouldBe 2
    }

    @Test
    fun `a stale hide leaves an indicator shown after it started`() {
        val state = GestureIndicatorState()
        state.show(SwipeTarget.VOLUME, 0.3f)
        val stale = state.revision
        state.show(SwipeTarget.VOLUME, 0.3f)

        state.hide(stale)

        state.indicator shouldBe GestureIndicator(SwipeTarget.VOLUME, 0.3f)
    }
}
