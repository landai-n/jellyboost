package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The receiver has let go of the film and the coordinator's grace period is running
 * (`PlayerEvent.RemoteItemMissing`, not yet `onCastItemLost`). Whatever the receiver holds now, which
 * may be another sender's media, is not this screen's to pause or seek: the label says Play, and Play
 * sends the film back, as it does once the grace period is over (`PlayerViewModelCastTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastGraceTest : PlayerViewModelCastFixture() {
    /** Playing on the television with a valid reading taken, then let go of — the grace period running. */
    private suspend fun TestScope.letGoWithinGrace(): PlayerViewModel {
        val model = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.playWhenReady = true
        castHandle.snapshot = ON_THE_TELEVISION
        model.onTick(ON_THE_TELEVISION)
        castHandle.emit(PlayerEvent.IsPlayingChanged(true))
        advanceUntilIdle()
        castHandle.resetCalls()

        castHandle.snapshot = NOT_OURS
        castHandle.emit(PlayerEvent.RemoteItemMissing(ON_THE_TELEVISION))
        // Whatever the television holds now means to play, and buffers.
        castHandle.playWhenReady = true
        castHandle.emit(PlayerEvent.Buffering(true))
        runCurrent()
        model.onTick(NOT_OURS)
        return model
    }

    @Test
    fun `inside the grace period the screen offers Play, and play sends the film back instead of pausing`() =
        runTest(dispatcher) {
            val model = letGoWithinGrace()
            val requests = recordResolves()
            val state = model.uiState.value

            val label = transportControl(state.showsPlaying, state.showsBufferingRing)
            model.togglePlayPause()
            runCurrent()

            label shouldBe TransportControl(action = TransportAction.PLAY, showsRing = false)
            // Not a pause on another sender's film.
            castHandle.pauseCount shouldBe 0
            castHandle.playCount shouldBe 0
            requests.single().castTarget shouldBe true
            requests.single().startPositionTicks shouldBe ON_THE_TELEVISION.positionMs.millisToTicks()
            castHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `a resend inside the grace period closes the dropped session first, once`() =
        runTest(dispatcher) {
            val model = letGoWithinGrace()
            recordResolves()

            model.togglePlayPause()
            runCurrent()

            coVerify(exactly = 1) { reporter.reportStop(source, ON_THE_TELEVISION) }
        }

    @Test
    fun `a skip inside the grace period moves the resume point without touching the receiver`() =
        runTest(dispatcher) {
            val model = letGoWithinGrace()

            model.seekBy(-10_000L)

            castHandle.seekedToMs.shouldBeEmpty()
            model.position.value.positionMs shouldBe ON_THE_TELEVISION.positionMs - 10_000L
        }

    @Test
    fun `a film that comes back within the grace period is paused by the tap, as its label says`() =
        runTest(dispatcher) {
            val model = letGoWithinGrace()
            castHandle.emit(PlayerEvent.RemoteItemMissingCleared)
            runCurrent()
            val state = model.uiState.value

            val label = transportControl(state.showsPlaying, state.showsBufferingRing).action
            model.togglePlayPause()

            label shouldBe TransportAction.PAUSE
            castHandle.pauseCount shouldBe 1
            castHandle.prepared.shouldBeEmpty()
        }

    private companion object {
        /** Fifteen minutes in, on the television. */
        val ON_THE_TELEVISION = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)

        /** A receiver holding nothing of ours. */
        val NOT_OURS = PlaybackSnapshot(isValid = false)
    }
}
