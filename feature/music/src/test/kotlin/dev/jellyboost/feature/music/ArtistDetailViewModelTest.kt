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

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {
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
    fun `loads the artist, their albums and their top tracks concurrently`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Success(artist())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns
                AppResult.Success(listOf(album("a1", "In Rainbows")))
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns
                AppResult.Success(listOf(track("t1", "Weird Fishes")))

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.artist?.name shouldBe "Radiohead"
            state.albums.map { it.name } shouldContainExactly listOf("In Rainbows")
            state.topTracks.map { it.name } shouldContainExactly listOf("Weird Fishes")
        }

    @Test
    fun `collapses top tracks to five until expanded`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Success(artist())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns AppResult.Success(emptyList())
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns
                AppResult.Success((1..8).map { track("t$it", "Track $it") })

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.visibleTopTracks shouldHaveSizeOf 5
            viewModel.uiState.value.hasMoreTopTracks shouldBe true

            viewModel.expandTopTracks()

            viewModel.uiState.value.visibleTopTracks shouldHaveSizeOf 8
        }

    @Test
    fun `a failed artist fetch surfaces the error`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Failure(AppError.Network())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns AppResult.Success(emptyList())
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns AppResult.Success(emptyList())

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.errorMessage
                .shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `toggling favourite writes through the local-first repository`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Success(artist())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns AppResult.Success(emptyList())
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleFavorite(viewModel.uiState.value.artist!!)
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite(ARTIST_ID, true) }
        }

    @Test
    fun `download badges reach both the albums and the top tracks`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Success(artist())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns
                AppResult.Success(listOf(album("a1", "The Bends")))
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns
                AppResult.Success(listOf(track("t1", "Fake Plastic Trees")))
            downloadStates.value = mapOf("a1" to DownloadState.Downloaded, "t1" to DownloadState.Queued)

            val viewModel = viewModel()
            advanceUntilIdle()

            // Both halves, not just the tracks: an album card carries a badge too.
            viewModel.uiState.value.albums
                .single()
                .downloadState shouldBe DownloadState.Downloaded
            viewModel.uiState.value.topTracks
                .single()
                .downloadState shouldBe DownloadState.Queued
        }

    @Test
    fun `a badge that changes after the page loaded still reaches it`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ARTIST_ID) } returns AppResult.Success(artist())
            coEvery { repository.getArtistAlbums(ARTIST_ID) } returns
                AppResult.Success(listOf(album("a1", "The Bends")))
            coEvery { repository.getArtistTopTracks(ARTIST_ID) } returns AppResult.Success(emptyList())
            val viewModel = viewModel()
            advanceUntilIdle()

            downloadStates.value = mapOf("a1" to DownloadState.Downloading(0.5f))
            advanceUntilIdle()

            viewModel.uiState.value.albums
                .single()
                .downloadState shouldBe DownloadState.Downloading(0.5f)
        }

    private infix fun List<*>.shouldHaveSizeOf(expected: Int) {
        size shouldBe expected
    }

    private fun viewModel() =
        ArtistDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            savedStateHandle = SavedStateHandle(mapOf("artistId" to ARTIST_ID)),
        )

    private fun artist() = JellyfinItem(id = ARTIST_ID, name = "Radiohead", type = ItemType.MUSIC_ARTIST)

    private fun album(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MUSIC_ALBUM)

    private fun track(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO)

    private companion object {
        const val ARTIST_ID = "artist-1"
    }
}
