package dev.jellyboost.feature.music.nowplaying

import app.cash.turbine.test
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
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
        }

    private val userDataChanges =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val userDataRepository =
        mockk<UserDataRepository> {
            every { changes } returns userDataChanges
            coEvery { setFavorite(any(), any()) } returns AppResult.Success(UserData())
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

    private fun viewModel() = NowPlayingViewModel(controller = controller, userDataRepository = userDataRepository)

    private fun activeState() =
        MusicPlaybackState.Active(
            queue = listOf(JellyfinItem(id = "t1", name = "Track 1", type = ItemType.AUDIO)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 5_000L,
            durationMs = 180_000L,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.OFF,
        )
}
