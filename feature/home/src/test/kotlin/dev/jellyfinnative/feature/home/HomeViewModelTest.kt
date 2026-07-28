package dev.jellyfinnative.feature.home

import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.userdata.UserDataChange
import dev.jellyfinnative.data.userdata.UserDataEventBus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [HomeViewModel]'s load, failure and refresh behaviour. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val eventBus = UserDataEventBus()

    private val movies = LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)
    private val shows = LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts in the loading state`() =
        runTest(dispatcher) {
            stubEverythingEmpty()

            val viewModel = HomeViewModel(repository, eventBus)

            viewModel.uiState.value.isLoading shouldBe true
        }

    @Test
    fun `loads every home row in jellyfin-web's order`() =
        runTest(dispatcher) {
            val resumeItem = episode("e1", "Trompe L'Oeil")
            val nextUpItem = episode("e2", "Chestnut")
            val movie = movie("m1", "Dune")

            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia("lib-movies", any()) } returns AppResult.Success(listOf(movie))
            coEvery { repository.getLatestMedia("lib-shows", any()) } returns AppResult.Success(emptyList())

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.errorMessage.shouldBeNull()
            state.libraries shouldContainExactly listOf(movies, shows)
            state.resume shouldContainExactly listOf(resumeItem)
            state.nextUp shouldContainExactly listOf(nextUpItem)
            // Empty "Latest" sections are dropped, exactly as jellyfin-web omits empty shelves.
            state.latest.map { it.library } shouldContainExactly listOf(movies)
            state.latest.single().items shouldContainExactly listOf(movie)
        }

    @Test
    fun `requests one latest row per library`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies, shows))

            HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getLatestMedia("lib-movies", any()) }
            coVerify(exactly = 1) { repository.getLatestMedia("lib-shows", any()) }
        }

    @Test
    fun `emits loading then loaded`() =
        runTest(dispatcher) {
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))

            val viewModel = HomeViewModel(repository, eventBus)

            viewModel.uiState.test {
                awaitItem().isLoading shouldBe true
                advanceUntilIdle()
                val loaded = awaitItem()
                loaded.isLoading shouldBe false
                loaded.libraries shouldContainExactly listOf(movies)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces an error when the libraries call fails`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoading shouldBe false
            state.errorMessage!! shouldContain "server"
            state.libraries.shouldBeEmpty()
        }

    @Test
    fun `does not call the row endpoints when the libraries call fails`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Unauthorized())

            HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getResumeItems(any()) }
            coVerify(exactly = 0) { repository.getNextUp(any()) }
        }

    @Test
    fun `leaves a failing row empty instead of blanking the screen`() =
        runTest(dispatcher) {
            val nextUpItem = episode("e2", "Chestnut")
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Failure(AppError.Server(503))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(emptyList())

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.errorMessage.shouldBeNull()
            state.resume.shouldBeEmpty()
            state.nextUp shouldContainExactly listOf(nextUpItem)
        }

    @Test
    fun `reports an empty state when the server has nothing to show`() =
        runTest(dispatcher) {
            stubEverythingEmpty()

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            viewModel.uiState.value.isEmpty shouldBe true
        }

    @Test
    fun `refresh re-fetches every row and clears a previous error`() =
        runTest(dispatcher) {
            coEvery { repository.getUserViews() } returns AppResult.Failure(AppError.Network())

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()
            viewModel.uiState.value.errorMessage!! shouldContain "server"

            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.errorMessage.shouldBeNull()
            state.isRefreshing shouldBe false
            state.libraries shouldContainExactly listOf(movies)
            coVerify(exactly = 2) { repository.getUserViews() }
        }

    // ---- M4: user-data event bus --------------------------------------------------------------

    @Test
    fun `patches a loaded row when user data changes elsewhere, without refetching`() =
        runTest(dispatcher) {
            val resumeItem = episode("e1", "Trompe L'Oeil")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(shows))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(resumeItem))

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()
            viewModel.uiState.value.resume
                .single()
                .userData.played shouldBe false

            eventBus.emit(UserDataChange("e1", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value.resume
                .single()
                .userData.played shouldBe true
            // The whole point: no second round-trip for any row.
            coVerify(exactly = 1) { repository.getUserViews() }
            coVerify(exactly = 1) { repository.getResumeItems(any()) }
        }

    @Test
    fun `patches the same item across every row it appears in`() =
        runTest(dispatcher) {
            val nextUpItem = episode("e2", "Chestnut")
            val movie = movie("m1", "Dune")
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getResumeItems(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getNextUp(any()) } returns AppResult.Success(listOf(nextUpItem))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()

            eventBus.emit(UserDataChange("e2", UserData(isFavorite = true)))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.resume
                .single()
                .userData.isFavorite shouldBe true
            state.nextUp
                .single()
                .userData.isFavorite shouldBe true
            // An untouched row keeps its identity so Compose can skip it entirely.
            state.latest
                .single()
                .items
                .single()
                .userData.isFavorite shouldBe false
        }

    @Test
    fun `ignores a change for an item no row is showing`() =
        runTest(dispatcher) {
            val movie = movie("m1", "Dune")
            stubEverythingEmpty()
            coEvery { repository.getUserViews() } returns AppResult.Success(listOf(movies))
            coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(listOf(movie))

            val viewModel = HomeViewModel(repository, eventBus)
            advanceUntilIdle()
            val before = viewModel.uiState.value

            eventBus.emit(UserDataChange("somewhere-else", UserData(played = true)))
            advanceUntilIdle()

            viewModel.uiState.value shouldBe before
        }

    private fun stubEverythingEmpty() {
        coEvery { repository.getUserViews() } returns AppResult.Success(emptyList())
        coEvery { repository.getResumeItems(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUp(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getLatestMedia(any(), any()) } returns AppResult.Success(emptyList())
    }

    private fun episode(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.EPISODE, seriesName = "Westworld")

    private fun movie(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MOVIE)
}
