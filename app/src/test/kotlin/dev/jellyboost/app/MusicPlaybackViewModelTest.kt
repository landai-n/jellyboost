package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Unit tests for [MusicPlaybackViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaybackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val controllerState = MutableStateFlow<MusicPlaybackState>(MusicPlaybackState.Idle)
    private val controller =
        mockk<MusicController> {
            every { state } returns controllerState
            every { messages } returns emptyFlow<MusicMessage>()
            coEvery { play(any(), any(), any(), any()) } returns true
            every { togglePlayPause() } returns Unit
            every { next() } returns Unit
        }

    private fun viewModel() = MusicPlaybackViewModel(controller)

    @Test
    fun `state is the controller's own state, passed straight through`() =
        runTest {
            viewModel().state.test {
                awaitItem() shouldBe MusicPlaybackState.Idle
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `play forwards the track list and start index, unshuffled, from position zero`() =
        runTest(dispatcher) {
            val track = track()
            viewModel().play(listOf(track), startIndex = 1)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(listOf(track), 1, false, 0L) }
        }

    @Test
    fun `shuffle starts the queue at index zero, shuffled`() =
        runTest(dispatcher) {
            val tracks = listOf(track())
            viewModel().shuffle(tracks)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(tracks, 0, true, 0L) }
        }

    @Test
    fun `playResumed converts the saved ticks to milliseconds and starts a single-item queue`() =
        runTest(dispatcher) {
            // 42 seconds, in Jellyfin's 100ns ticks.
            val resumable = track().copy(userData = UserData(playbackPositionTicks = 420_000_000L))

            viewModel().playResumed(resumable)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(listOf(resumable), 0, false, 42_000L) }
        }

    @Test
    fun `togglePlayPause and next forward straight to the controller`() =
        runTest {
            val viewModel = viewModel()

            viewModel.togglePlayPause()
            viewModel.next()

            verify(exactly = 1) { controller.togglePlayPause() }
            verify(exactly = 1) { controller.next() }
        }

    private fun track() = JellyfinItem(id = "t1", name = "Track 1", type = ItemType.AUDIO)
}
