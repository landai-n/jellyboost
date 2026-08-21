package dev.jellyboost.feature.music.nowplaying

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [NowPlayingViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val controllerState = MutableStateFlow<MusicPlaybackState>(MusicPlaybackState.Idle)
    private val controller =
        mockk<MusicController> {
            every { state } returns controllerState
            every { messages } returns emptyFlow<MusicMessage>()
            every { togglePlayPause() } returns Unit
            every { next() } returns Unit
            every { previous() } returns Unit
            every { seekTo(any()) } returns Unit
            every { setShuffle(any()) } returns Unit
            every { cycleRepeat() } returns Unit
            every { jumpTo(any()) } returns Unit
            every { removeAt(any()) } returns Unit
            every { moveItem(any(), any()) } returns Unit
            every { stop() } returns Unit
        }

    private val userDataChanges =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val userDataRepository =
        mockk<UserDataRepository> {
            every { changes } returns userDataChanges
            coEvery { setFavorite(any(), any()) } returns AppResult.Success(UserData())
        }

    private val repository =
        mockk<JellyfinRepository> {
            coEvery { getLyrics(any()) } returns AppResult.Failure(AppError.NotFound("x"))
        }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `idle controller state maps to an idle ui state`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.isIdle shouldBe true
            viewModel.uiState.value.track shouldBe null
        }

    @Test
    fun `an active queue is reflected straight through to the ui state`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            // `uiState` is `WhileSubscribed`, so the combine only runs while something is
            // collecting it — exactly like a screen would through `collectAsStateWithLifecycle`.
            viewModel.uiState.test {
                awaitItem().isIdle shouldBe true

                controllerState.value = activeState()

                val state = awaitItem()
                state.isIdle shouldBe false
                state.track?.id shouldBe "t1"
                state.isPlaying shouldBe true
                state.positionMs shouldBe 5_000L
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `seekTo forwards straight to the controller`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.seekTo(12_000L)

            verify(exactly = 1) { controller.seekTo(12_000L) }
        }

    @Test
    fun `jumpTo, removeAt and moveItem forward to the controller`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.jumpTo(2)
            viewModel.removeAt(1)
            viewModel.moveItem(0, 3)

            verify(exactly = 1) { controller.jumpTo(2) }
            verify(exactly = 1) { controller.removeAt(1) }
            verify(exactly = 1) { controller.moveItem(0, 3) }
        }

    @Test
    fun `stop forwards straight to the controller — the Stop button ends the session`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.stop()

            verify(exactly = 1) { controller.stop() }
        }

    @Test
    fun `toggleFavorite flips the current track through the local-first repository`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            // Briefly subscribed so the queue state actually reaches `uiState.value` — see the
            // previous test's note on `WhileSubscribed`. `StateFlow.value` keeps whatever it last
            // published after the subscriber leaves, which is what lets `toggleFavorite` below read
            // it synchronously the way a click handler does.
            viewModel.uiState.test {
                awaitItem()
                controllerState.value = activeState()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.toggleFavorite()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite("t1", true) }
        }

    @Test
    fun `toggleFavorite is a no-op while idle`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            coVerify(exactly = 0) { userDataRepository.setFavorite(any(), any()) }
        }

    @Test
    fun `a user-data change patches the playing track's favourite without a fresh queue snapshot`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem()
                controllerState.value = activeState()
                awaitItem()

                userDataChanges.emit(UserDataChange(itemId = "t1", userData = UserData(isFavorite = true)))

                awaitItem().track?.userData?.isFavorite shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- lyrics (M13 Phase 6) -------------------------------------------------------------------
    //
    // The lyrics fetch is a second, asynchronous contributor to `uiState` alongside the queue
    // transition itself (a track becomes current with `lyrics = null` for one tick, then the fetch
    // resolves and patches it in), so these drive a background collector to steady state with
    // `advanceUntilIdle()` and read `uiState.value` rather than awaiting Turbine items one at a
    // time — the item count downstream of one `controllerState` write is not fixed.

    @Test
    fun `lyrics are fetched for the current track and land in the ui state`() =
        runTest(dispatcher) {
            coEvery { repository.getLyrics("t1") } returns AppResult.Success(lyricsOf("La la la"))

            val viewModel = viewModel()
            val collector = launch { viewModel.uiState.collect {} }
            controllerState.value = activeState()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.lyrics?.lines?.map { it.text } shouldBe listOf("La la la")
            state.lyricsAvailable shouldBe true
            collector.cancel()
        }

    @Test
    fun `no lyrics for the track reads as unavailable, not an error`() =
        runTest(dispatcher) {
            coEvery { repository.getLyrics("t1") } returns AppResult.Failure(AppError.NotFound("t1"))

            val viewModel = viewModel()
            val collector = launch { viewModel.uiState.collect {} }
            controllerState.value = activeState()
            advanceUntilIdle()

            viewModel.uiState.value.lyricsAvailable shouldBe false
            collector.cancel()
        }

    @Test
    fun `switching tracks re-fetches lyrics for the new track`() =
        runTest(dispatcher) {
            coEvery { repository.getLyrics("t1") } returns AppResult.Success(lyricsOf("Track one"))
            coEvery { repository.getLyrics("t2") } returns AppResult.Success(lyricsOf("Track two"))

            val viewModel = viewModel()
            val collector = launch { viewModel.uiState.collect {} }
            controllerState.value = activeState()
            advanceUntilIdle()

            controllerState.value = activeState(id = "t2")
            advanceUntilIdle()

            viewModel.uiState.value.lyrics
                ?.lines
                ?.map { it.text } shouldBe listOf("Track two")
            coVerify(exactly = 1) { repository.getLyrics("t1") }
            coVerify(exactly = 1) { repository.getLyrics("t2") }
            collector.cancel()
        }

    @Test
    fun `a queue reorder that keeps the same track does not re-fetch its lyrics`() =
        runTest(dispatcher) {
            coEvery { repository.getLyrics("t1") } returns AppResult.Failure(AppError.NotFound("t1"))

            val viewModel = viewModel()
            val collector = launch { viewModel.uiState.collect {} }
            controllerState.value = activeState()
            advanceUntilIdle()

            // Same current track (t1), different transport state — a `moveItem`/shuffle change.
            controllerState.value = activeState().copy(shuffleEnabled = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getLyrics("t1") }
            collector.cancel()
        }

    @Test
    fun `going idle clears the lyrics cache, so returning to the same track re-fetches`() =
        runTest(dispatcher) {
            coEvery { repository.getLyrics("t1") } returns AppResult.Success(lyricsOf("La la la"))

            val viewModel = viewModel()
            val collector = launch { viewModel.uiState.collect {} }
            controllerState.value = activeState()
            advanceUntilIdle()
            viewModel.uiState.value.lyricsAvailable shouldBe true

            controllerState.value = MusicPlaybackState.Idle
            advanceUntilIdle()
            viewModel.uiState.value.lyrics shouldBe null

            controllerState.value = activeState()
            advanceUntilIdle()

            viewModel.uiState.value.lyricsAvailable shouldBe true
            coVerify(exactly = 2) { repository.getLyrics("t1") }
            collector.cancel()
        }

    private fun viewModel() =
        NowPlayingViewModel(controller = controller, userDataRepository = userDataRepository, repository = repository)

    private fun lyricsOf(text: String) =
        Lyrics(lines = listOf(LyricLine(startTicks = null, text = text)), isSynced = false)

    private fun activeState(id: String = "t1") =
        MusicPlaybackState.Active(
            queue = listOf(JellyfinItem(id = id, name = "Track 1", type = ItemType.AUDIO)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 5_000L,
            durationMs = 180_000L,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.OFF,
        )
}
