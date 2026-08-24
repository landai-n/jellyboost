package dev.jellyboost.feature.search

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The debounce is exercised on virtual time: "one request per pause in typing", not "eventually". */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
        }

    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    private val queries = mutableListOf<ItemQuery>()

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @BeforeEach
    fun setUp() {
        coEvery { repository.getItems(capture(queries)) } returns AppResult.Success(emptyList())
    }

    @Test
    fun `does not search before the debounce elapses`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("westworld")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 1)
            runCurrent()

            coVerify(exactly = 0) { repository.getItems(any()) }
        }

    @Test
    fun `searches once the debounce elapses`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("westworld")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS + 1)
            runCurrent()

            coVerify(exactly = 1) { repository.getItems(any()) }
            queries.single().searchTerm shouldBe "westworld"
        }

    @Test
    fun `typing a whole word costs a single request`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            "west".forEachIndexed { index, _ ->
                viewModel.onQueryChange("west".take(index + 1))
                advanceTimeBy(TYPING_INTERVAL_MILLIS)
                runCurrent()
            }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getItems(any()) }
            queries.single().searchTerm shouldBe "west"
        }

    @Test
    fun `a pause mid-typing costs a second request`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("west")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS + 1)
            runCurrent()
            viewModel.onQueryChange("westworld")
            advanceUntilIdle()

            queries.map { it.searchTerm } shouldContainExactly listOf("west", "westworld")
        }

    @Test
    fun `whitespace-only input never reaches the server`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("   ")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getItems(any()) }
            viewModel.uiState.value.hasSearched shouldBe false
        }

    @Test
    fun `clearing the field empties the results without waiting`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))
            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()
            viewModel.uiState.value.movies
                .map { it.name } shouldContainExactly listOf("Dune")

            viewModel.clearQuery()
            runCurrent()

            val state = viewModel.uiState.value
            state.query shouldBe ""
            state.movies.shouldBeEmpty()
            state.hasSearched shouldBe false
        }

    @Test
    fun `asks for the video and M13 music types, capped at fifty`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("dune")
            advanceUntilIdle()

            val query = queries.single()
            query.itemTypes shouldContainExactly
                listOf(
                    ItemType.MOVIE,
                    ItemType.SERIES,
                    ItemType.EPISODE,
                    ItemType.MUSIC_ARTIST,
                    ItemType.MUSIC_ALBUM,
                    ItemType.AUDIO,
                    ItemType.PLAYLIST,
                )
            query.recursive shouldBe true
            query.limit shouldBe 50
        }

    @Test
    fun `trims the term before sending it`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("  dune  ")
            advanceUntilIdle()

            queries.single().searchTerm shouldBe "dune"
        }

    @Test
    fun `splits one response into the three sections the screen draws`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns
                AppResult.Success(
                    listOf(
                        movie("m1", "Westward"),
                        series("s1", "Westworld"),
                        episode("e1", "The Original"),
                        movie("m2", "West Side Story"),
                    ),
                )
            val viewModel = startedViewModel()

            viewModel.onQueryChange("west")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.movies.map { it.name } shouldContainExactly listOf("Westward", "West Side Story")
            state.series.map { it.name } shouldContainExactly listOf("Westworld")
            state.episodes.map { it.name } shouldContainExactly listOf("The Original")
            state.submittedQuery shouldBe "west"
            state.hasSearched shouldBe true
            state.isSearching shouldBe false
            state.resultCount shouldBe 4
        }

    @Test
    fun `splits one response into the four M13 music sections too`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns
                AppResult.Success(
                    listOf(
                        artist("ar1", "Radiohead"),
                        album("al1", "The Bends"),
                        song("so1", "Fake Plastic Trees"),
                        playlist("pl1", "Road Trip"),
                    ),
                )
            val viewModel = startedViewModel()

            viewModel.onQueryChange("radiohead")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.artists.map { it.name } shouldContainExactly listOf("Radiohead")
            state.albums.map { it.name } shouldContainExactly listOf("The Bends")
            state.songs.map { it.name } shouldContainExactly listOf("Fake Plastic Trees")
            state.playlists.map { it.name } shouldContainExactly listOf("Road Trip")
            state.hasNoResults shouldBe false
        }

    @Test
    fun `reports an empty result set as a completed search`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.onQueryChange("zzzz")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.hasSearched shouldBe true
            state.hasNoResults shouldBe true
            state.resultCount shouldBe 0
            state.error.shouldBeNull()
        }

    @Test
    fun `clearing the field takes the announced result count back to zero`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns
                AppResult.Success(listOf(movie("m1", "Westward"), series("s1", "Westworld")))
            val viewModel = startedViewModel()

            viewModel.onQueryChange("west")
            advanceUntilIdle()
            viewModel.uiState.value.resultCount shouldBe 2

            viewModel.clearQuery()
            advanceUntilIdle()

            viewModel.uiState.value.resultCount shouldBe 0
            viewModel.uiState.value.hasSearched shouldBe false
        }

    @Test
    fun `shows the searching state while a request is in flight`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()

            viewModel.uiState.test {
                awaitItem().isSearching shouldBe false
                viewModel.onQueryChange("dune")
                awaitItem().query shouldBe "dune"
                advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS + 1)
                runCurrent()
                awaitItem().isSearching shouldBe true
                advanceUntilIdle()
                awaitItem().isSearching shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces a failure and drops stale results`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns AppResult.Failure(AppError.Network())
            val viewModel = startedViewModel()

            viewModel.onQueryChange("dune")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.error.shouldBeInstanceOf<AppError.Network>()
            state.isSearching shouldBe false
            state.hasNoResults shouldBe true
            state.resultCount shouldBe 0
        }

    @Test
    fun `retry re-runs the current search and clears the error`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns AppResult.Failure(AppError.Server(503))
            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()
            val failed = viewModel.uiState.value
            failed.error.shouldBeInstanceOf<AppError.Server>()

            coEvery { repository.getItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))
            viewModel.retry()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.error.shouldBeNull()
            state.movies.map { it.name } shouldContainExactly listOf("Dune")
        }

    @Test
    fun `re-runs the current term when the server becomes reachable again`() =
        runTest(dispatcher) {
            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.getItems(any()) }

            coEvery { repository.getItems(capture(queries)) } returns
                AppResult.Success(listOf(movie("m1", "Dune")))
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getItems(any()) }
            queries.last().searchTerm shouldBe "dune"
            viewModel.uiState.value.query shouldBe "dune"
            viewModel.uiState.value.movies
                .map { it.name } shouldContainExactly listOf("Dune")
        }

    @Test
    fun `an empty search box has nothing to re-run on a reconnect`() =
        runTest(dispatcher) {
            startedViewModel()
            advanceUntilIdle()

            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.getItems(any()) }
        }

    /** Builds the ViewModel and lets its debounce collector start before the test types. */
    private fun TestScope.startedViewModel(): SearchViewModel {
        val viewModel = SearchViewModel(repository, downloads, connectivityRefresher)
        runCurrent()
        return viewModel
    }

    @Test
    fun `download state reaches the result cards`() =
        runTest(dispatcher) {
            coEvery { repository.getItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))
            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()

            downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
            advanceUntilIdle()

            viewModel.uiState.value.movies
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    @Test
    fun `a download state that arrived before the search survives it`() =
        runTest(dispatcher) {
            downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
            coEvery { repository.getItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()

            viewModel.uiState.value.movies
                .single()
                .downloadState shouldBe DownloadState.Downloaded
        }

    /**
     * A badge is decoration; the screen behind it is not. Unguarded, a throw would kill the collector
     * and freeze every badge at its last value — marks the user would read as current.
     */
    @Test
    fun `a failing download-state flow degrades to no badges and leaves the results intact`() =
        runTest(dispatcher) {
            every { downloads.observeStates() } returns
                flow {
                    emit(mapOf("m1" to DownloadState.Downloaded))
                    error("badge flow died")
                }
            coEvery { repository.getItems(any()) } returns AppResult.Success(listOf(movie("m1", "Dune")))

            val viewModel = startedViewModel()
            viewModel.onQueryChange("dune")
            advanceUntilIdle()

            viewModel.uiState.value.movies
                .single()
                .downloadState shouldBe DownloadState.NotDownloaded
            viewModel.uiState.value
                .error
                .shouldBeNull()
        }

    private fun movie(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MOVIE)

    private fun series(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.SERIES)

    private fun episode(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.EPISODE, seriesName = "Westworld")

    private fun artist(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MUSIC_ARTIST)

    private fun album(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MUSIC_ALBUM)

    private fun song(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.AUDIO)

    private fun playlist(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.PLAYLIST)

    private companion object {
        /** Fast typing: well inside the debounce window. */
        const val TYPING_INTERVAL_MILLIS = 80L
    }
}
