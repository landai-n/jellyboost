package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The transport on **this device** — the sibling of the cast fixes in `PlayerViewModelCastTest`: the
 * toggle reads the player's intent, the skips measure from a valid reading, and a local rebuffer is
 * shown the same way a receiver's is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerTransportTest : PlayerViewModelFixture() {
    // ---- play / pause -----------------------------------------------------------------------------

    @Test
    fun `a tap on a playing film pauses it, and the next plays it again`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.playWhenReady shouldBe true

            model.togglePlayPause()

            playerHandle.pauseCount shouldBe 1
            playerHandle.playWhenReady shouldBe false

            model.togglePlayPause()

            playerHandle.playCount shouldBe 1
            playerHandle.playWhenReady shouldBe true
        }

    @Test
    fun `a tap while rebuffering pauses, though nothing is playing at that instant`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            // Waiting for data: not playing, but it means to — the tap means "stop that".
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, isPlaying = false)
            playerHandle.playWhenReady = true

            model.togglePlayPause()

            playerHandle.pauseCount shouldBe 1
            playerHandle.playCount shouldBe 0
        }

    // ---- skips ------------------------------------------------------------------------------------

    @Test
    fun `a skip lands relative to where the film is`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, durationMs = 7_200_000L)

            model.seekBy(30_000L)
            model.seekBy(-10_000L)

            playerHandle.seekedToMs shouldBe listOf(90_000L, 80_000L)
        }

    @Test
    fun `a skip stops at either end of a film of known length`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 7_190_000L, durationMs = 7_200_000L)

            model.seekBy(30_000L)
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 5_000L, durationMs = 7_200_000L)
            model.seekBy(-10_000L)

            playerHandle.seekedToMs shouldBe listOf(7_200_000L, 0L)
        }

    @Test
    fun `a film of unknown length is not clamped to zero`() =
        runTest(dispatcher) {
            // Neither the server nor the container has said how long it is.
            coEvery { resolver.resolve(any()) } returns AppResult.Success(source.copy(runTimeTicks = 0L))
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, durationMs = 0L)

            model.seekBy(30_000L)

            playerHandle.seekedToMs shouldBe listOf(90_000L)
        }

    // ---- buffering --------------------------------------------------------------------------------

    @Test
    fun `a local rebuffer is shown, and cleared when it is over`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Ready)
            playerHandle.emit(PlayerEvent.IsPlayingChanged(true))
            advanceUntilIdle()
            model.uiState.value.isBuffering shouldBe false

            // The order the shared listener delivers a rebuffer in: the individual callback first,
            // then its batch's `Buffering`.
            playerHandle.emit(PlayerEvent.IsPlayingChanged(false))
            playerHandle.emit(PlayerEvent.Buffering(true))
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe true
            model.uiState.value.showsBufferingRing shouldBe true

            playerHandle.emit(PlayerEvent.Ready)
            playerHandle.emit(PlayerEvent.IsPlayingChanged(true))
            playerHandle.emit(PlayerEvent.Buffering(false))
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe false
        }

    @Test
    fun `a rebuffer is answered by a working pause`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Ready)
            playerHandle.emit(PlayerEvent.IsPlayingChanged(false))
            playerHandle.emit(PlayerEvent.Buffering(true))
            advanceUntilIdle()
            val state = model.uiState.value

            // What the transport row draws and what a tap on it does must agree: pause.
            transportControl(state.showsPlaying, state.showsBufferingRing) shouldBe
                TransportControl(action = TransportAction.PAUSE, showsRing = true)
            model.togglePlayPause()
            playerHandle.pauseCount shouldBe 1
            playerHandle.playCount shouldBe 0
        }

    @Test
    fun `an open that means to play is shown buffering until the player is ready`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.prepared.single().playWhenReady shouldBe true
            model.uiState.value.isBuffering shouldBe true

            playerHandle.emit(PlayerEvent.Ready)
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe false
        }

    @Test
    fun `a stop in playing does not clear a buffering that is still going on`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.emit(PlayerEvent.Buffering(true))
            advanceUntilIdle()

            // A batch boundary can put the `false` after the `Buffering(true)` it belongs with.
            playerHandle.emit(PlayerEvent.IsPlayingChanged(false))
            advanceUntilIdle()

            model.uiState.value.isBuffering shouldBe true
        }
}
