package dev.jellyboost.player.ui

import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What a load carries, and what the transport says, come from the player's **intent**
 * (`playWhenReady`, and the receiver's own settled-paused state), never from a snapshot. A receiver's
 * snapshot is invalid for seconds after every load and says "not playing" while it buffers, and
 * `openForCast` now honours the flag it is given, so a snapshot-derived `false` reloads a playing
 * television paused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelCastIntentTest : PlayerViewModelCastFixture() {
    private suspend fun TestScope.castingMeaningToPlay(): PlayerViewModel {
        val model = castViewModel()
        advanceUntilIdle()
        framework.onSessionStarted("Living Room TV")
        advanceUntilIdle()
        castHandle.playWhenReady = true
        castHandle.snapshot = NOT_OURS
        castHandle.resetCalls()
        return model
    }

    @Test
    fun `a re-negotiation during an invalid reading reloads the receiver playing, as it meant to`() =
        runTest(dispatcher) {
            val model = castingMeaningToPlay()
            recordResolves()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            castHandle.prepared.last().playWhenReady shouldBe true
        }

    @Test
    fun `a re-negotiation of a receiver settled paused under a stale intent reloads it paused`() =
        runTest(dispatcher) {
            val model = castingMeaningToPlay()
            castHandle.isSettledPaused = true
            recordResolves()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            castHandle.prepared.last().playWhenReady shouldBe false
        }

    @Test
    fun `a phone buffering toward play hands the film over playing`() =
        runTest(dispatcher) {
            castViewModel()
            advanceUntilIdle()
            // Buffering: meaning to play, not (yet) playing.
            local.playWhenReady = true
            local.snapshot = PlaybackSnapshot(positionMs = 600_000L, isPlaying = false)
            recordResolves()

            framework.onSessionStarted("Living Room TV")
            advanceUntilIdle()

            castHandle.prepared.single().playWhenReady shouldBe true
        }

    @Test
    fun `a receiver settled paused under a stale intent shows Play, and a tap plays it`() =
        runTest(dispatcher) {
            val model = castingMeaningToPlay()
            // `CastPlayer` masks `isPlaying` from `playWhenReady`, so the reading claims playing.
            castHandle.snapshot = PlaybackSnapshot(positionMs = 900_000L, isPlaying = true)
            castHandle.isSettledPaused = true
            // Settled paused is, by definition, not buffering: the post-load ring has cleared.
            castHandle.emit(PlayerEvent.Buffering(false))
            advanceUntilIdle()

            model.onTick(castHandle.snapshot)
            val state = model.uiState.value
            val label = transportControl(state.showsPlaying, state.showsBufferingRing).action
            model.togglePlayPause()

            label shouldBe TransportAction.PLAY
            castHandle.playCount shouldBe 1
            castHandle.pauseCount shouldBe 0
        }

    private companion object {
        val NOT_OURS = PlaybackSnapshot(isValid = false)
    }
}
