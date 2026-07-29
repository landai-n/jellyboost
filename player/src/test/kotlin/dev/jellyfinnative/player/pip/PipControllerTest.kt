package dev.jellyfinnative.player.pip

import app.cash.turbine.test
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PipController] and the aspect-ratio clamp it carries.
 *
 * The clamp is not cosmetic: `PictureInPictureParams.setAspectRatio` throws outside 1:2.39 … 2.39:1,
 * and it is called as the user presses Home — so an unclamped 2.76:1 film would crash the app at
 * exactly the moment it was trying to be helpful.
 */
class PipControllerTest {
    private val controller = PipController()

    @Test
    fun `nothing may float until the player says so`() {
        controller.state.value.canEnter shouldBe false
        controller.state.value.aspectRatio
            .shouldBeNull()
    }

    @Test
    fun `publishes what the player screen reports`() =
        runTest {
            controller.state.test {
                awaitItem().canEnter shouldBe false

                controller.setPlayerState(active = true, videoWidth = 1920, videoHeight = 1080)
                val playing = awaitItem()
                playing.canEnter shouldBe true
                playing.aspectRatio shouldBe (1920 to 1080)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the system's mode change is recorded without disturbing readiness`() {
        controller.setPlayerState(active = true, videoWidth = 1920, videoHeight = 1080)

        controller.setInPictureInPicture(true)

        controller.state.value.isInPictureInPicture shouldBe true
        controller.state.value.canEnter shouldBe true
    }

    @Test
    fun `clearing forgets everything`() {
        controller.setPlayerState(active = true, videoWidth = 1920, videoHeight = 1080)
        controller.setInPictureInPicture(true)

        controller.clear()

        controller.state.value shouldBe PipState()
    }

    @Test
    fun `an unknown video size offers no aspect ratio hint`() {
        PipState(videoWidth = 0, videoHeight = 0).aspectRatio.shouldBeNull()
    }

    @Test
    fun `an ultra-wide film is clamped to what Android accepts`() {
        // Ultra Panavision 70, 2.76:1 — wider than the 2.39:1 the platform allows.
        PipState(videoWidth = 2760, videoHeight = 1000).aspectRatio shouldBe (239 to 100)
    }

    @Test
    fun `an extremely tall video is clamped the other way`() {
        PipState(videoWidth = 500, videoHeight = 2000).aspectRatio shouldBe (100 to 239)
    }

    @Test
    fun `an ordinary widescreen ratio is passed through untouched`() {
        // 2.13:1 — inside the platform's limits, so the window matches the film exactly.
        PipState(videoWidth = 1920, videoHeight = 900).aspectRatio shouldBe (1920 to 900)
    }
}
