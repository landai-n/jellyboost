package dev.jellyfinnative.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.FilterOptions
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.SortBy
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [LibraryViewModel]'s query building, sort/filter handling and facet loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    /** The badge source (M7); emits an empty map unless a test says otherwise. */
    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
        }

    /** Every query the grid asked the repository for, in order. */
    private val queries = mutableListOf<ItemQuery>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.getItemsPaged(capture(queries)) } returns
            flowOf(PagingData.from(listOf(movie("m1", "Dune"))))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- route arguments --------------------------------------------------------------------

    @Test
    fun `takes the library name from the route so the top bar renders immediately`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.value.libraryName shouldBe "Movies"
        }

    @Test
    fun `starts sorted by name, ascending, unfiltered`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            val state = viewModel.uiState.value
            state.sortBy shouldBe SortBy.SORT_NAME
            state.sortOrder shouldBe SortOrder.ASCENDING
            state.filters shouldBe FilterOptions()
            state.activeFilterCount shouldBe 0
        }

    // ---- the paged query --------------------------------------------------------------------

    @Test
    fun `pages the library it was opened for, top-level titles only`() =
        runTest(dispatcher) {
            collectingItems(viewModel()) {
                queries.single().parentId shouldBe LIBRARY_ID
                queries.single().itemTypes shouldContainExactly listOf(ItemType.MOVIE, ItemType.SERIES)
                queries.single().recursive shouldBe true
                queries.single().sortBy shouldBe SortBy.SORT_NAME
            }
        }

    @Test
    fun `picking a different sort key re-queries in that key's natural direction`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.selectSort(SortBy.DATE_CREATED)
                advanceUntilIdle()

                queries.map { it.sortBy } shouldContainExactly
                    listOf(SortBy.SORT_NAME, SortBy.DATE_CREATED)
                // "Date added" reads newest-first, as it does in jellyfin-web.
                queries.last().sortOrder shouldBe SortOrder.DESCENDING
            }
        }

    @Test
    fun `picking the active sort key flips the direction`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.selectSort(SortBy.SORT_NAME)
                advanceUntilIdle()

                viewModel.uiState.value.sortOrder shouldBe SortOrder.DESCENDING
                queries.map { it.sortOrder } shouldContainExactly
                    listOf(SortOrder.ASCENDING, SortOrder.DESCENDING)
            }
        }

    @Test
    fun `toggling the direction re-queries without changing the key`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.toggleSortOrder()
                advanceUntilIdle()

                queries.last().sortBy shouldBe SortBy.SORT_NAME
                queries.last().sortOrder shouldBe SortOrder.DESCENDING
            }
        }

    @Test
    fun `editing the draft filters does not touch the server`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.updateDraftFilters(FilterOptions(genres = listOf("Thriller")))
                advanceUntilIdle()

                // A chip tap must not re-query a 500-item library.
                queries.size shouldBe 1
                viewModel.uiState.value.filters shouldBe FilterOptions()
            }
        }

    @Test
    fun `applying the draft filters re-queries and closes the sheet`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.updateDraftFilters(
                    FilterOptions(genres = listOf("Thriller"), isPlayed = false),
                )
                viewModel.applyFilters()
                advanceUntilIdle()

                queries.size shouldBe 2
                queries.last().filters.genres shouldContainExactly listOf("Thriller")
                queries.last().filters.isPlayed shouldBe false
                viewModel.uiState.value.isFilterSheetOpen shouldBe false
                viewModel.uiState.value.activeFilterCount shouldBe 2
            }
        }

    @Test
    fun `clearing the filters re-queries unfiltered`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.updateDraftFilters(FilterOptions(years = listOf(2021)))
                viewModel.applyFilters()
                advanceUntilIdle()

                viewModel.clearFilters()
                advanceUntilIdle()

                queries.last().filters shouldBe FilterOptions()
                viewModel.uiState.value.activeFilterCount shouldBe 0
            }
        }

    @Test
    fun `opening and closing the sheet never re-queries`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Success(FilterFacets(genres = listOf("Drama")))
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.openFilterSheet()
                advanceUntilIdle()
                viewModel.dismissFilterSheet()
                advanceUntilIdle()

                queries.size shouldBe 1
            }
        }

    // ---- filter facets ----------------------------------------------------------------------

    @Test
    fun `opening the sheet loads this library's facets`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Success(FilterFacets(genres = listOf("Drama"), years = listOf(2021)))
            val viewModel = viewModel()

            viewModel.openFilterSheet()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isFilterSheetOpen shouldBe true
            state.areFacetsLoading shouldBe false
            state.facets.genres shouldContainExactly listOf("Drama")
            state.facets.years shouldContainExactly listOf(2021)
            coVerify(exactly = 1) {
                repository.getFilterFacets(LIBRARY_ID, listOf(ItemType.MOVIE, ItemType.SERIES))
            }
        }

    @Test
    fun `the facets are fetched once, even when the server has nothing to offer`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns AppResult.Success(FilterFacets())
            val viewModel = viewModel()

            viewModel.openFilterSheet()
            advanceUntilIdle()
            viewModel.dismissFilterSheet()
            viewModel.openFilterSheet()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getFilterFacets(any(), any()) }
        }

    @Test
    fun `a facet failure is surfaced without closing the sheet`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Failure(AppError.Network())
            val viewModel = viewModel()

            viewModel.openFilterSheet()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isFilterSheetOpen shouldBe true
            state.areFacetsLoading shouldBe false
            state.facetsError.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `retrying after a facet failure clears the error`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Failure(AppError.Server(statusCode = 503))
            val viewModel = viewModel()
            viewModel.openFilterSheet()
            advanceUntilIdle()

            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Success(FilterFacets(genres = listOf("Drama")))
            viewModel.retryFacets()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.facetsError.shouldBeNull()
            state.facets.genres shouldContainExactly listOf("Drama")
        }

    // ---- M7: download badges -------------------------------------------------------------------

    @Test
    fun `a download state change re-maps the loaded pages without re-querying the server`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                queries.clear()
                downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
                advanceUntilIdle()

                // `cachedIn` sits upstream of the badge combine on purpose: otherwise every
                // throttled progress write would reload the whole grid from the server.
                queries.shouldBeEmpty()
            }
        }

    @Test
    fun `the grid still asks the server exactly once per query`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                downloadStates.value = mapOf("m1" to DownloadState.Queued)
                advanceUntilIdle()
                downloadStates.value = mapOf("m1" to DownloadState.Downloaded)
                advanceUntilIdle()

                queries.size shouldBe 1
            }
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun viewModel() =
        LibraryViewModel(
            repository = repository,
            downloads = downloads,
            savedStateHandle =
                SavedStateHandle(
                    mapOf("libraryId" to LIBRARY_ID, "libraryName" to "Movies"),
                ),
        )

    /**
     * Runs [block] while the paged flow is being collected — collection is what makes the `Pager`
     * ask the repository at all. The collection is cancelled afterwards: a paged flow never
     * completes, so leaving it running would hang `runTest`.
     */
    private fun TestScope.collectingItems(
        viewModel: LibraryViewModel,
        block: TestScope.() -> Unit,
    ) {
        val collection = launch { viewModel.items.collect { } }
        advanceUntilIdle()
        try {
            block()
        } finally {
            collection.cancel()
        }
    }

    private fun movie(
        id: String,
        name: String,
    ) = JellyfinItem(id = id, name = name, type = ItemType.MOVIE)

    private companion object {
        const val LIBRARY_ID = "lib-movies"
    }
}
