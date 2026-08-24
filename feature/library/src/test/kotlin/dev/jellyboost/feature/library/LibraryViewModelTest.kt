package dev.jellyboost.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.selection.BatchOutcome
import dev.jellyboost.core.common.selection.BatchReport
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.userdata.UserDataChange
import dev.jellyboost.data.userdata.UserDataRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Unit tests for [LibraryViewModel]'s query building, sort/filter handling and facet loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()

    /** The badge source; emits an empty map unless a test says otherwise. */
    private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns downloadStates
        }

    /** The local-first watched/favourite writer, and the bus the grid patches its cards from. */
    private val userDataChanges =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val userDataRepository =
        mockk<UserDataRepository> {
            every { changes } returns userDataChanges
        }

    /** The connectivity-change signal; fires only when a test says the server came back. */
    private val connectivityChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns connectivityChanges
        }

    /** Every query the grid asked the repository for, in order. */
    private val queries = mutableListOf<ItemQuery>()

    /**
     * The total-count callbacks handed to the repository alongside those queries.
     *
     * Captured rather than ignored so a test can play the part of the paging source and report a
     * total the way the first page of a real one does.
     */
    private val totalCountSinks = mutableListOf<(Int) -> Unit>()

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @BeforeEach
    fun setUp() {
        every { repository.getItemsPaged(capture(queries), capture(totalCountSinks)) } returns
            flowOf(PagingData.from(listOf(movie("m1", "Dune"))))
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

    // ---- the inline filter chips ---------------------------------------------------------------

    @Test
    fun `a chip commits straight onto the grid, with no draft stage`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()

                queries.size shouldBe 2
                queries.last().filters.isPlayed shouldBe false
                // The sheet opens on what the chips did rather than reverting it.
                viewModel.uiState.value.draftFilters.isPlayed shouldBe false
            }
        }

    @Test
    fun `tapping an applied chip removes that filter`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()
                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()

                queries.last().filters shouldBe FilterOptions()
                viewModel.uiState.value.activeFilterCount shouldBe 0
            }
        }

    @Test
    fun `the two watched chips are exclusive, as the sheet's three-way row is`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()
                viewModel.toggleFilterChip(LibraryFilterChip.Watched)
                advanceUntilIdle()

                queries.last().filters.isPlayed shouldBe true
                viewModel.uiState.value.activeFilterCount shouldBe 1
            }
        }

    @Test
    fun `an applied genre is offered as a chip that can drop it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.updateDraftFilters(FilterOptions(genres = listOf("Thriller")))
                viewModel.applyFilters()
                advanceUntilIdle()

                viewModel.uiState.value.filterChips shouldContain LibraryFilterChip.Genre("Thriller")

                viewModel.toggleFilterChip(LibraryFilterChip.Genre("Thriller"))
                advanceUntilIdle()

                queries
                    .last()
                    .filters.genres
                    .shouldBeEmpty()
            }
        }

    @Test
    fun `a chip ends selection mode, as every other re-query does`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.onSelection(SelectionIntent.Toggle("m1"))
                viewModel.uiState.value.totalCount
                    .shouldBeNull()

                viewModel.toggleFilterChip(LibraryFilterChip.Watched)
                advanceUntilIdle()

                viewModel.selection.value.isActive shouldBe false
            }
        }

    // ---- the item count ------------------------------------------------------------------------

    @Test
    fun `the total the first page reports becomes the header's count`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                advanceUntilIdle()
                // What the paging source does once the server has counted the library.
                totalCountSinks.last().invoke(412)

                viewModel.uiState.value.totalCount shouldBe 412
            }
        }

    @Test
    fun `changing the filters drops the count until the new page reports one`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                advanceUntilIdle()
                totalCountSinks.last().invoke(412)

                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()

                viewModel.uiState.value.totalCount
                    .shouldBeNull()

                totalCountSinks.last().invoke(37)
                viewModel.uiState.value.totalCount shouldBe 37
            }
        }

    @Test
    fun `a count from a query the user has moved on from is ignored`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                advanceUntilIdle()
                val staleSink = totalCountSinks.last()

                viewModel.toggleFilterChip(LibraryFilterChip.Unwatched)
                advanceUntilIdle()

                // The `Pager` that was swapped out finishes counting *after* the swap.
                staleSink.invoke(412)

                viewModel.uiState.value.totalCount
                    .shouldBeNull()
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

    // ---- download badges -------------------------------------------------------------------

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

    // ---- refresh when connectivity changes ---------------------------------------------------------------

    @Test
    fun `re-loads the facets it had already fetched when the server becomes reachable again`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Success(FilterFacets(genres = listOf("Drama")))
            val viewModel = viewModel()
            viewModel.openFilterSheet()
            advanceUntilIdle()

            coEvery { repository.getFilterFacets(any(), any()) } returns
                AppResult.Success(FilterFacets(genres = listOf("Drama", "Thriller")))
            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.getFilterFacets(any(), any()) }
            viewModel.uiState.value.facets.genres shouldContainExactly listOf("Drama", "Thriller")
        }

    @Test
    fun `a reconnect does not fetch facets the screen never asked for`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns AppResult.Success(FilterFacets())
            viewModel()
            advanceUntilIdle()

            connectivityChanges.emit(Unit)
            advanceUntilIdle()

            // The sheet was never opened, so there is nothing stale to replace — and the facets are
            // still fetched on the first open, as they always were.
            coVerify(exactly = 0) { repository.getFilterFacets(any(), any()) }
        }

    @Test
    fun `a reconnect leaves the grid to the pager`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                // `collectingItems` runs a non-suspending block; the buffered emission reaches the
                // ViewModel's collector on the `advanceUntilIdle` below all the same.
                connectivityChanges.tryEmit(Unit)
                advanceUntilIdle()

                // `getItemsPaged` re-decides online/offline on every connection change itself, so a
                // second trigger here would only make the grid fetch twice.
                queries.size shouldBe 1
            }
        }

    // ---- batch selection (docs/features/batch-selection.md) -------------------------------------

    @Test
    fun `long-pressing a card enters selection mode and a second tap leaves it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.selection.value.isActive shouldBe false
            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.selection.value.count shouldBe 1

            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.selection.value.isActive shouldBe false
        }

    @Test
    fun `the close affordance leaves selection mode`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.onSelection(SelectionIntent.Clear)

            viewModel.selection.value.isActive shouldBe false
        }

    @Test
    fun `the paged grid offers no Select all, and ignores one if asked`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.onSelection(SelectionIntent.SelectAll)

            // "All" on a paged grid would mean either "the pages loaded so far" — a different set
            // after every scroll — or a per-page walk of the whole library. The bar does not show
            // the action; this pins that the ViewModel would not act on it either.
            viewModel.selection.value.ids shouldContainExactly setOf("m1")
        }

    @Test
    fun `a selection survives new pages and a download-badge change`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                viewModel.onSelection(SelectionIntent.Toggle("m1"))
                downloadStates.value = mapOf("m1" to DownloadState.Downloading(progress = 0.4f))
                advanceUntilIdle()

                // The selection is a set of ids in its own flow, so nothing the pager or the
                // download pipeline does underneath it can disturb it.
                viewModel.selection.value.ids shouldContainExactly setOf("m1")
            }
        }

    @Test
    fun `changing the sort clears the selection`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.selectSort(SortBy.DATE_CREATED)

            // The query is swapped underneath, so a kept selection would be ids the user can no
            // longer see — and the next batch would act on items that are not on screen.
            viewModel.selection.value.isActive shouldBe false
        }

    @Test
    fun `applying filters clears the selection, opening the sheet does not`() =
        runTest(dispatcher) {
            coEvery { repository.getFilterFacets(any(), any()) } returns AppResult.Success(FilterFacets())
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.openFilterSheet()
            advanceUntilIdle()
            viewModel.updateDraftFilters(FilterOptions(genres = listOf("Thriller")))
            viewModel.selection.value.isActive shouldBe true

            viewModel.applyFilters()
            viewModel.selection.value.isActive shouldBe false
        }

    @Test
    fun `marking the selection watched writes one call per card, locally first`() =
        runTest(dispatcher) {
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.onSelection(SelectionIntent.Toggle("m2"))

            viewModel.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed("m1", true) }
            coVerify(exactly = 1) { userDataRepository.setPlayed("m2", true) }
            viewModel.uiState.value.userMessage shouldBe
                BatchReport(SelectionAction.MARK_WATCHED, BatchOutcome(done = 2))
        }

    @Test
    fun `marking the selection unwatched writes played false`() =
        runTest(dispatcher) {
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.onSelection(SelectionIntent.Run(SelectionAction.MARK_UNWATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed("m1", false) }
        }

    @Test
    fun `a batch runs every item and counts the failures rather than stopping at the first`() =
        runTest(dispatcher) {
            coEvery { userDataRepository.setPlayed("m1", any()) } returns AppResult.Failure(AppError.Storage())
            coEvery { userDataRepository.setPlayed("m2", any()) } returns AppResult.Success(UserData())
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.onSelection(SelectionIntent.Toggle("m2"))

            viewModel.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            advanceUntilIdle()

            coVerify(exactly = 1) { userDataRepository.setPlayed("m2", true) }
            viewModel.uiState.value.userMessage shouldBe
                BatchReport(SelectionAction.MARK_WATCHED, BatchOutcome(done = 1, failed = 1))
        }

    @Test
    fun `downloading the selection skips what is already on the device`() =
        runTest(dispatcher) {
            coEvery { downloads.enqueue(any()) } returns AppResult.Success(Unit)
            downloadStates.value = mapOf("m1" to DownloadState.Downloaded, "m2" to DownloadState.Queued)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.onSelection(SelectionIntent.Toggle("m2"))
            viewModel.onSelection(SelectionIntent.Toggle("m3"))
            viewModel.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            // Only the card with no row of its own is enqueued. A series never has one — the
            // pipeline expands it — so it always reaches the enqueuer, which skips the episodes
            // already downloaded itself.
            coVerify(exactly = 0) { downloads.enqueue("m1") }
            coVerify(exactly = 0) { downloads.enqueue("m2") }
            coVerify(exactly = 1) { downloads.enqueue("m3") }
            viewModel.uiState.value.userMessage shouldBe
                BatchReport(SelectionAction.DOWNLOAD, BatchOutcome(done = 1, skipped = 2))
        }

    @Test
    fun `a failed enqueue is counted and reported`() =
        runTest(dispatcher) {
            coEvery { downloads.enqueue("m1") } returns AppResult.Failure(AppError.Network())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onSelection(SelectionIntent.Toggle("m1"))
            viewModel.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            viewModel.uiState.value.userMessage shouldBe
                BatchReport(SelectionAction.DOWNLOAD, BatchOutcome(done = 0, failed = 1))
        }

    @Test
    fun `selection mode ends as the batch starts, and the snackbar is one-shot`() =
        runTest(dispatcher) {
            coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
            val viewModel = viewModel()
            viewModel.onSelection(SelectionIntent.Toggle("m1"))

            viewModel.onSelection(SelectionIntent.Run(SelectionAction.MARK_WATCHED))
            viewModel.selection.value.isActive shouldBe false

            advanceUntilIdle()
            val message = viewModel.uiState.value.userMessage
            message.shouldNotBeNull()

            viewModel.consumeMessage()
            val consumed = viewModel.uiState.value.userMessage
            consumed.shouldBeNull()
        }

    @Test
    fun `an action with nothing selected does nothing at all`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onSelection(SelectionIntent.Run(SelectionAction.DOWNLOAD))
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.enqueue(any()) }
            val message = viewModel.uiState.value.userMessage
            message.shouldBeNull()
        }

    @Test
    fun `a user-data change patches the loaded pages without re-querying the server`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            collectingItems(viewModel) {
                queries.clear()
                userDataChanges.tryEmit(UserDataChange("m1", UserData(played = true)))
                advanceUntilIdle()

                // The Swiftfin pattern: a batch *Mark watched* is reflected on the cards
                // from the local write, never from a refetch.
                queries.shouldBeEmpty()
            }
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun viewModel() =
        LibraryViewModel(
            repository = repository,
            downloads = downloads,
            userDataRepository = userDataRepository,
            connectivityRefresher = connectivityRefresher,
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
