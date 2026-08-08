package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.data.JellyfinRepository
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
    private val repository = mockk<JellyfinRepository>()

    private fun viewModel() = MusicPlaybackViewModel(controller, repository)

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

    // ---- playDownloadedAudio (Downloads tab, M13 review fix) -----------------------------------

    @Test
    fun `playDownloadedAudio plays the downloaded album context, starting at the tapped track`() =
        runTest(dispatcher) {
            val tapped = track().copy(id = "t2", albumId = "album-1")
            val albumTracks = listOf(track(), tapped, track().copy(id = "t3"))
            coEvery { repository.getAlbumTracks("album-1") } returns AppResult.Success(albumTracks)

            // 42 seconds, in Jellyfin's 100ns ticks — the Downloads row's resume position.
            viewModel().playDownloadedAudio(tapped, startPositionTicks = 420_000_000L)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(albumTracks, 1, false, 42_000L) }
        }

    @Test
    fun `playDownloadedAudio falls back to a single-item queue when the track has no album`() =
        runTest(dispatcher) {
            val tapped = track() // albumId defaults to null

            viewModel().playDownloadedAudio(tapped)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(listOf(tapped), 0, false, 0L) }
            coVerify(exactly = 0) { repository.getAlbumTracks(any()) }
        }

    @Test
    fun `playDownloadedAudio falls back to a single-item queue when the album fetch fails`() =
        runTest(dispatcher) {
            val tapped = track().copy(albumId = "album-1")
            coEvery { repository.getAlbumTracks("album-1") } returns AppResult.Failure(AppError.Network())

            viewModel().playDownloadedAudio(tapped)
            advanceUntilIdle()

            // Offline with no album rows cached, a server hiccup — either way the tap still plays.
            coVerify(exactly = 1) { controller.play(listOf(tapped), 0, false, 0L) }
        }

    // ---- startRadio (M13 Phase 6) --------------------------------------------------------------

    @Test
    fun `startRadio hands a non-empty mix straight to the queue`() =
        runTest(dispatcher) {
            val seed = track()
            val mix = listOf(track().copy(id = "m1"), track().copy(id = "m2"))
            coEvery { repository.getInstantMix(seed.id) } returns AppResult.Success(mix)

            viewModel().startRadio(seed)
            advanceUntilIdle()

            coVerify(exactly = 1) { controller.play(mix, 0, false, 0L) }
        }

    @Test
    fun `startRadio reports RadioFailed when the mix comes back empty`() =
        runTest(dispatcher) {
            val seed = track()
            coEvery { repository.getInstantMix(seed.id) } returns AppResult.Success(emptyList())

            val viewModel = viewModel()
            viewModel.messages.test {
                viewModel.startRadio(seed)

                awaitItem() shouldBe MusicMessage.RadioFailed("Track 1")
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) { controller.play(any(), any(), any(), any()) }
        }

    @Test
    fun `startRadio reports RadioFailed when the fetch fails`() =
        runTest(dispatcher) {
            val seed = track()
            coEvery { repository.getInstantMix(seed.id) } returns AppResult.Failure(AppError.Network())

            val viewModel = viewModel()
            viewModel.messages.test {
                viewModel.startRadio(seed)

                awaitItem() shouldBe MusicMessage.RadioFailed("Track 1")
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun track() = JellyfinItem(id = "t1", name = "Track 1", type = ItemType.AUDIO)
}
