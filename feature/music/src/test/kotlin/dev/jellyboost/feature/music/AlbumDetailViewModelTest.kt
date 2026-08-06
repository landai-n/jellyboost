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

/** Unit tests for [AlbumDetailViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelTest {
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
    fun `loads the album and its tracks concurrently`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(listOf(track("t1", "Track 1", disc = 1, index = 1)))

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.album?.name shouldBe "The Bends"
            state.tracks.map { it.name } shouldContainExactly listOf("Track 1")
        }

    @Test
    fun `a single disc of tracks is not grouped`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(
                    listOf(track("t1", "Track 1", disc = 1, index = 1), track("t2", "Track 2", disc = 1, index = 2)),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.isMultiDisc shouldBe false
        }

    @Test
    fun `tracks spanning more than one disc are grouped`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(
                    listOf(track("t1", "Track 1", disc = 1, index = 1), track("t2", "Track 2", disc = 2, index = 1)),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isMultiDisc shouldBe true
            state.tracksByDisc.map { it.first } shouldContainExactly listOf(1, 2)
        }

    @Test
    fun `a failed album fetch surfaces the error and drops any tracks that arrived`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Failure(AppError.NotFound("x"))
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns AppResult.Success(emptyList())

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.album shouldBe null
            state.errorMessage.shouldBeInstanceOf<AppError.NotFound>()
        }

    @Test
    fun `toggling favourite flips the current state through the local-first repository`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleFavorite(viewModel.uiState.value.album!!)
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite(ALBUM_ID, true) }
        }

    @Test
    fun `a user-data change patches the matching track in place`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(listOf(track("t1", "Track 1", disc = 1, index = 1)))
            val viewModel = viewModel()
            advanceUntilIdle()

            userDataChanges.emit(UserDataChange(itemId = "t1", userData = UserData(isFavorite = true)))
            advanceUntilIdle()

            viewModel.uiState.value.tracks
                .single()
                .userData.isFavorite shouldBe true
        }

    @Test
    fun `refresh re-fetches the album and its tracks`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getItem(ALBUM_ID) }
        }

    @Test
    fun `reconnecting refetches the album`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getItem(ALBUM_ID) }
        }

    // ---- downloads (M13 Phase 5) -----------------------------------------------------------------

    @Test
    fun `downloading the album enqueues the album id, not the track ids`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(listOf(track("t1", "Track 1", disc = 1, index = 1)))
            coEvery { downloads.enqueue(any()) } returns AppResult.Success(Unit)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.downloadAlbum()
            advanceUntilIdle()

            // `DownloadEnqueuer` is the one place that knows a music container expands into its
            // tracks, in the album's own disc/track order, skipping what is already downloaded.
            coVerify(exactly = 1) { downloads.enqueue(ALBUM_ID) }
            coVerify(exactly = 0) { downloads.enqueue("t1") }
        }

    @Test
    fun `an album whose every track is downloaded reads as downloaded and queues nothing more`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(
                    listOf(
                        track("t1", "Track 1", disc = 1, index = 1),
                        track("t2", "Track 2", disc = 1, index = 2),
                    ),
                )
            coEvery { downloads.enqueue(any()) } returns AppResult.Success(Unit)
            downloadStates.value =
                mapOf("t1" to DownloadState.Downloaded, "t2" to DownloadState.Downloaded)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.albumDownloadState shouldBe DownloadState.Downloaded
            viewModel.uiState.value.canDownload shouldBe false

            viewModel.downloadAlbum()
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.enqueue(any()) }
        }

    @Test
    fun `a half-downloaded album reports the share of it that is on the device`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns
                AppResult.Success(
                    listOf(
                        track("t1", "Track 1", disc = 1, index = 1),
                        track("t2", "Track 2", disc = 1, index = 2),
                    ),
                )
            downloadStates.value = mapOf("t1" to DownloadState.Downloaded)
            val viewModel = viewModel()
            advanceUntilIdle()

            // A finished track counts as one and a transferring one as its fraction, so the control
            // reads "half the album" rather than the progress of whichever file is moving.
            viewModel.uiState.value.albumDownloadState shouldBe DownloadState.NotDownloaded

            downloadStates.value =
                mapOf("t1" to DownloadState.Downloaded, "t2" to DownloadState.Downloading(0.5f))
            advanceUntilIdle()

            viewModel.uiState.value.albumDownloadState shouldBe DownloadState.Downloading(0.75f)
            viewModel.uiState.value.canDownload shouldBe false
        }

    @Test
    fun `an album with no tracks offers no download`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ALBUM_ID) } returns AppResult.Success(album())
            coEvery { repository.getAlbumTracks(ALBUM_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.albumDownloadState shouldBe DownloadState.NotDownloaded
            viewModel.uiState.value.canDownload shouldBe false
        }

    private fun viewModel() =
        AlbumDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            savedStateHandle = SavedStateHandle(mapOf("albumId" to ALBUM_ID)),
        )

    private fun album() =
        JellyfinItem(id = ALBUM_ID, name = "The Bends", type = ItemType.MUSIC_ALBUM, albumArtist = "Radiohead")

    private fun track(
        id: String,
        name: String,
        disc: Int,
        index: Int,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO, parentIndexNumber = disc, indexNumber = index)

    private companion object {
        const val ALBUM_ID = "album-1"
    }
}
