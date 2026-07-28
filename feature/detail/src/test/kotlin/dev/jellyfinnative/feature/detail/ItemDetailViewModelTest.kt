package dev.jellyfinnative.feature.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.userdata.UserDataChange
import dev.jellyfinnative.data.userdata.UserDataRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [ItemDetailViewModel] — load shapes, toggles and the event-bus patch. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val userDataRepository = mockk<UserDataRepository>()
    private val changes =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val movie =
        JellyfinItem(id = ITEM_ID, name = "Arrival", type = ItemType.MOVIE, productionYear = 2016)
    private val series = JellyfinItem(id = ITEM_ID, name = "Westworld", type = ItemType.SERIES)
    private val season =
        JellyfinItem(id = ITEM_ID, name = "Season 1", type = ItemType.SEASON, seriesId = SERIES_ID)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userDataRepository.changes } returns changes
        coEvery { repository.getSeasons(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getEpisodes(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUpForSeries(any()) } returns AppResult.Success(null)
        coEvery { repository.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- loading ------------------------------------------------------------------------------

    @Test
    fun `starts in the loading state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            viewModel().uiState.value.isLoading shouldBe true
        }

    @Test
    fun `a movie loads its item and a More like this row, nothing else`() =
        runTest(dispatcher) {
            val related = JellyfinItem(id = "m2", name = "Sicario", type = ItemType.MOVIE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { repository.getSimilarItems(ITEM_ID, any()) } returns AppResult.Success(listOf(related))

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.isLoaded shouldBe true
            state.item!!.name shouldBe "Arrival"
            state.similar shouldContainExactly listOf(related)
            state.seasons.shouldBeEmpty()
            state.episodes.shouldBeEmpty()
            coVerify(exactly = 0) { repository.getSeasons(any()) }
            coVerify(exactly = 0) { repository.getEpisodes(any(), any()) }
        }

    @Test
    fun `a series loads its seasons and next up`() =
        runTest(dispatcher) {
            val seasonItem = JellyfinItem(id = "s1", name = "Season 1", type = ItemType.SEASON)
            val next = JellyfinItem(id = "e1", name = "Chestnut", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Success(listOf(seasonItem))
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns AppResult.Success(next)

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.seasons shouldContainExactly listOf(seasonItem)
            state.nextUp shouldBe next
            state.episodes.shouldBeEmpty()
        }

    @Test
    fun `a season loads its episodes, scoped to its series`() =
        runTest(dispatcher) {
            val episode = JellyfinItem(id = "e1", name = "The Original", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns AppResult.Success(listOf(episode))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.episodes shouldContainExactly listOf(episode)
            coVerify(exactly = 1) { repository.getEpisodes(SERIES_ID, ITEM_ID) }
            // A season is browsed through its series, so "more like this" would be noise.
            coVerify(exactly = 0) { repository.getSimilarItems(any(), any()) }
        }

    @Test
    fun `surfaces an error when the item itself cannot be loaded`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.isLoading shouldBe false
            state.errorMessage!! shouldContain "server"
            state.item.shouldBeNull()
        }

    @Test
    fun `leaves a failing related row empty instead of blanking the page`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Failure(AppError.Server(503))

            val model = viewModel()
            advanceUntilIdle()

            val state = model.uiState.value
            state.errorMessage.shouldBeNull()
            state.item.shouldNotBeNull()
            state.seasons.shouldBeEmpty()
        }

    @Test
    fun `refresh re-fetches the item and clears a previous error`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.errorMessage!! shouldContain "server"

            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            model.refresh()
            advanceUntilIdle()

            model.uiState.value.errorMessage
                .shouldBeNull()
            coVerify(exactly = 2) { repository.getItem(ITEM_ID) }
        }

    // ---- user data ----------------------------------------------------------------------------

    @Test
    fun `mark watched toggles from the item's current state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(ITEM_ID, true) }
        }

    @Test
    fun `mark watched on an already watched item unmarks it`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(played = true)))
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed(ITEM_ID, false) }
        }

    @Test
    fun `favourite toggles from the item's current state`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setFavorite(any(), any()) } returns AppResult.Success(UserData())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleFavorite()
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setFavorite(ITEM_ID, true) }
        }

    @Test
    fun `reflects a toggle optimistically from the event bus, with no refetch`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData(played = true))

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            // The local write publishes on the bus; that is what flips the button.
            changes.emit(UserDataChange(ITEM_ID, UserData(played = true)))
            advanceUntilIdle()

            model.uiState.value.item!!
                .userData.played shouldBe true
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }
        }

    @Test
    fun `patches a season in the seasons row when its user data changes`() =
        runTest(dispatcher) {
            val seasonItem = JellyfinItem(id = "s1", name = "Season 1", type = ItemType.SEASON)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getSeasons(ITEM_ID) } returns AppResult.Success(listOf(seasonItem))

            val model = viewModel()
            advanceUntilIdle()

            changes.emit(UserDataChange("s1", UserData(played = true)))
            advanceUntilIdle()

            model.uiState.value.seasons
                .single()
                .userData.played shouldBe true
            model.uiState.value.item!!
                .userData.played shouldBe false
        }

    @Test
    fun `raises a message when a toggle cannot even be written locally`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            coEvery { userDataRepository.setFavorite(any(), any()) } returns
                AppResult.Failure(AppError.Storage())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleFavorite()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe UserMessage.UserDataWriteFailed
        }

    @Test
    fun `a toggle before the item has loaded does nothing`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Failure(AppError.Network())

            val model = viewModel()
            advanceUntilIdle()
            model.toggleWatched()
            advanceUntilIdle()

            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
        }

    // ---- not-yet-built actions ----------------------------------------------------------------

    @Test
    fun `play is honest about playback landing in M5`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.test {
                awaitItem().userMessage.shouldBeNull()
                model.onPlayClick()
                awaitItem().userMessage shouldBe UserMessage.PlaybackNotAvailableYet
                model.consumeMessage()
                awaitItem().userMessage.shouldBeNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `download is honest about the pipeline landing in M7`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()
            model.onDownloadClick()

            model.uiState.value.userMessage shouldBe UserMessage.DownloadNotAvailableYet
        }

    private fun viewModel() =
        ItemDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    private companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
    }
}
