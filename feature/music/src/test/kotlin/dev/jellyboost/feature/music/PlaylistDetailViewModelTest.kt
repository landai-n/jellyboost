package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaylistDetailViewModel].
 *
 * The "always empty offline" behaviour itself is [OfflineJellyfinRepository]'s to pin — this class
 * only has to show that whatever the repository answers with reaches the screen unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    private val userDataChanges =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val userDataRepository =
        mockk<UserDataRepository> {
            every { changes } returns userDataChanges
            coEvery { setFavorite(any(), any()) } returns AppResult.Success(UserData())
        }

    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads = mockk<DownloadRepository> { every { observeStates() } returns downloadStates }

    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns
                connectivityChanges
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
    fun `loads the playlist and its tracks in server order`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(PLAYLIST_ID) } returns AppResult.Success(playlist())
            coEvery { repository.getPlaylistItems(PLAYLIST_ID) } returns
                AppResult.Success(listOf(track("t2", "Track 2"), track("t1", "Track 1")))

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.playlist?.name shouldBe "Road Trip"
            state.tracks.map { it.name } shouldContainExactly listOf("Track 2", "Track 1")
        }

    @Test
    fun `an offline visit shows an empty track list rather than an error`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(PLAYLIST_ID) } returns AppResult.Success(playlist())
            coEvery { repository.getPlaylistItems(PLAYLIST_ID) } returns AppResult.Success(emptyList())

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.errorMessage shouldBe null
            state.tracks.shouldBeEmpty()
        }

    @Test
    fun `a failed playlist fetch surfaces the error`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(PLAYLIST_ID) } returns AppResult.Failure(AppError.Unauthorized())
            coEvery { repository.getPlaylistItems(PLAYLIST_ID) } returns AppResult.Success(emptyList())

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.errorMessage
                .shouldBeInstanceOf<AppError.Unauthorized>()
        }

    @Test
    fun `toggling favourite on a track writes through the local-first repository`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(PLAYLIST_ID) } returns AppResult.Success(playlist())
            coEvery { repository.getPlaylistItems(PLAYLIST_ID) } returns
                AppResult.Success(listOf(track("t1", "Track 1")))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleFavorite(
                viewModel.uiState.value.tracks
                    .single(),
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite("t1", true) }
        }

    @Test
    fun `a playlist's tracks carry the same download badge as anywhere else`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(PLAYLIST_ID) } returns AppResult.Success(playlist())
            coEvery { repository.getPlaylistItems(PLAYLIST_ID) } returns
                AppResult.Success(listOf(track("t1", "Track 1")))
            downloadStates.value = mapOf("t1" to DownloadState.Downloaded)

            val viewModel = viewModel()
            advanceUntilIdle()

            // Honest even though the playlist itself has no offline model: the badge describes the
            // *track's* file on this device, which is exactly what a playlist download produces.
            viewModel.uiState.value.tracks
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    private fun viewModel() =
        PlaylistDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to PLAYLIST_ID)),
        )

    private fun playlist() = JellyfinItem(id = PLAYLIST_ID, name = "Road Trip", type = ItemType.PLAYLIST)

    private fun track(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO)

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
    }
}
