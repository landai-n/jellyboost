package dev.jellyboost.feature.detail

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** The badge source (M7); emits an empty map unless a test says otherwise. */
    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** The on-device footprint of [ITEM_ID]; `null` unless a test says otherwise. */
    private val bytesOnDisk = MutableStateFlow<Long?>(null)
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
            every { observeBytesOnDisk(any()) } returns bytesOnDisk
        }

    /**
     * No group, in every test in this class (M11 Phase 4).
     *
     * That is the point of leaving it alone: SyncPlay's arrival must change nothing about an
     * ordinary detail page. The group actions themselves live in [ItemDetailGroupActionsTest].
     */
    private val syncPlaySession =
        mockk<SyncPlaySession>(relaxed = true) {
            every { activeGroup } returns MutableStateFlow(null)
        }

    /** The connectivity-change signal (M9); fires only when a test says the server came back. */
    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

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

    // ---- what Play actually plays ---------------------------------------------------------------

    @Test
    fun `a movie plays itself, from the start when it was never watched`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)

            val model = viewModel()
            advanceUntilIdle()

            val target = model.uiState.value.playTarget
            target!!.id shouldBe ITEM_ID
            playbackStartTicks(target) shouldBe 0L
        }

    @Test
    fun `a partly watched movie resumes where it was left`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(playbackPositionTicks = 600L)))

            val model = viewModel()
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 600L
        }

    @Test
    fun `a position written while offline turns the button into Resume, with no refetch`() =
        runTest(dispatcher) {
            // The offline half of the M8 chain: the player writes the position locally on every
            // tick and publishes it, and this screen is where the user sees it — a downloaded film
            // watched in airplane mode offers Resume at the right place with no server involved.
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            val model = viewModel()
            advanceUntilIdle()

            changes.emit(UserDataChange(ITEM_ID, UserData(playbackPositionTicks = 36_000_000_000L)))
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 36_000_000_000L
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }
        }

    @Test
    fun `a watched movie starts again from the beginning`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(userData = UserData(played = true, playbackPositionTicks = 600L)))

            val model = viewModel()
            advanceUntilIdle()

            playbackStartTicks(model.uiState.value.playTarget!!) shouldBe 0L
        }

    @Test
    fun `a series plays the episode the server calls next up`() =
        runTest(dispatcher) {
            val next = JellyfinItem(id = "e5", name = "Trompe L'Oeil", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns AppResult.Success(next)

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.playTarget shouldBe next
        }

    @Test
    fun `a season plays its first unwatched episode`() =
        runTest(dispatcher) {
            val watched =
                JellyfinItem(
                    id = "e1",
                    name = "The Original",
                    type = ItemType.EPISODE,
                    userData = UserData(played = true),
                )
            val next = JellyfinItem(id = "e2", name = "Chestnut", type = ItemType.EPISODE)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
                AppResult.Success(listOf(watched, next))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.playTarget shouldBe next
        }

    // ---- M9: refresh when connectivity changes ----------------------------------------------------------------

    @Test
    fun `re-fetches the item it is showing when the server becomes reachable again`() =
        runTest(dispatcher) {
            // Offline this page is a cached row, or a placeholder for something not downloaded.
            coEvery { repository.getItem(ITEM_ID) } returns
                AppResult.Success(movie.copy(name = "Arrival", available = false))
            val model = viewModel()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.getItem(ITEM_ID) }

            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getItem(ITEM_ID) }
            model.uiState.value.item!!
                .available shouldBe true
        }

    private fun viewModel() =
        ItemDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            syncPlaySession = syncPlaySession,
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    private companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
    }
}
