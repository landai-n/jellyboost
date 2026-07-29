package dev.jellyfinnative.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.FilterOptions
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.SortBy
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.common.selection.BatchReport
import dev.jellyfinnative.core.common.selection.ItemSelection
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.common.selection.SelectionIntent
import dev.jellyfinnative.core.common.selection.runBatch
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the library grid.
 *
 * Three streams, deliberately separate:
 * - [items] — the paged content. Its loading, error and empty states belong to Paging's
 *   `LoadState`, so this class never mirrors them into [uiState]; duplicating them is how a grid
 *   ends up showing a spinner over already-loaded items.
 * - [uiState] — everything the user can turn (sort, filters) plus the filter sheet.
 * - [selection] — the batch-selection set, apart from [uiState] so that a cell reading it is not
 *   also subscribed to the sort key and the filters (docs/features/batch-selection.md).
 *
 * Changing sort or filters produces a new [dev.jellyfinnative.core.common.model.ItemQuery], which
 * swaps the `Pager` underneath: `distinctUntilChanged` makes sure opening or closing the sheet, or
 * editing the draft filters, does not re-issue the query.
 *
 * `cachedIn(viewModelScope)` keeps the loaded pages across configuration changes — without it a
 * rotation re-fetches every page the user had scrolled through.
 *
 * @param savedStateHandle carries the `Routes.LibraryGrid` arguments; the library name is passed in
 *   the route so the top bar can render before the first page arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val downloads: DownloadRepository,
        private val userDataRepository: UserDataRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val libraryId: String = savedStateHandle.get<String>(KEY_LIBRARY_ID).orEmpty()

        private val _uiState =
            MutableStateFlow(
                LibraryUiState(libraryName = savedStateHandle.get<String>(KEY_LIBRARY_NAME).orEmpty()),
            )

        /** Sort, filters and sheet state. */
        val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        private val _selection = MutableStateFlow(ItemSelection())

        /**
         * Which cards are selected, in its **own** flow rather than inside [uiState].
         *
         * A grid cell has to read this to draw its indicator, and reading it out of [uiState] would
         * subscribe every visible cell to the sort key, the filters and the facets as well — so
         * opening the sort menu would recompose the whole visible grid. Separated, a toggle
         * invalidates only the cells whose own `selected` flag changed (see `LibraryGridScreen`).
         */
        val selection: StateFlow<ItemSelection> = _selection.asStateFlow()

        /**
         * The app-wide download-state map, mirrored here.
         *
         * Collected once and shared, rather than subscribed separately by the grid and by the batch
         * *Download* action: `observeStates()` is a Room Flow, and two collectors would be two
         * queries re-running on every throttled progress write.
         */
        private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

        /**
         * Local user-data changes seen since this screen opened, keyed by item id.
         *
         * The plan's Swiftfin pattern — "every list ViewModel patches in-memory items instantly"
         * (docs/PLAN.md, "Data layer") — applied to a paged grid: a batch *Mark watched* writes
         * locally and publishes on the bus, and the ticks appear on the cards without the grid
         * asking the server for anything.
         */
        private val userDataPatches = MutableStateFlow<Map<String, UserData>>(emptyMap())

        /**
         * The grid's paged content, with each card's download badge and local user data stamped on.
         *
         * `cachedIn` comes **before** both combines on purpose: it caches the pages, so a badge or a
         * watched tick changing re-maps the already-loaded pages in place instead of re-fetching
         * them from the server. Putting a combine upstream of `cachedIn` would make every progress
         * write a full reload of the grid.
         */
        val items: Flow<PagingData<JellyfinItem>> =
            _uiState
                .map { it.toQuery(libraryId) }
                .distinctUntilChanged()
                .flatMapLatest { repository.getItemsPaged(it) }
                .cachedIn(viewModelScope)
                .combine(downloadStates) { paging, states ->
                    paging.map { item ->
                        val next = states[item.id] ?: DownloadState.NotDownloaded
                        if (next == item.downloadState) item else item.copy(downloadState = next)
                    }
                }.combine(userDataPatches) { paging, patches ->
                    if (patches.isEmpty()) {
                        paging
                    } else {
                        paging.map { item ->
                            val next = patches[item.id]
                            if (next == null || next == item.userData) item else item.copy(userData = next)
                        }
                    }
                }

        init {
            observeConnectivityChanges()
            observeDownloadStates()
            observeUserDataChanges()
        }

        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads.observeStates().collect { states -> downloadStates.value = states }
            }
        }

        private fun observeUserDataChanges() {
            viewModelScope.launch {
                userDataRepository.changes.collect { change ->
                    userDataPatches.update { it + (change.itemId to change.userData) }
                }
            }
        }

        /**
         * On a connection change — in either direction — this re-loads **only the filter facets**:
         * the grid needs nothing, its `Pager` is rebuilt on every connection change inside
         * `getItemsPaged`, so the items swap source on their own (docs/features/offline-read.md).
         *
         * And only when the facets were already asked for: they are fetched the first time the
         * sheet opens, so a screen whose sheet was never opened has nothing stale to replace and
         * would just be spending a request.
         */
        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect {
                    val state = _uiState.value
                    val wasAsked = state.areFacetsLoaded || state.facetsError != null
                    if (wasAsked && !state.areFacetsLoading) {
                        retryFacets()
                    }
                }
            }
        }

        /** Applies a sort key; picking the key that is already active flips the direction. */
        fun selectSort(sortBy: SortBy) {
            dropSelection()
            _uiState.update { state ->
                if (state.sortBy == sortBy) {
                    state.copy(sortOrder = state.sortOrder.flipped())
                } else {
                    // A fresh key starts in its natural direction: A→Z for names, newest-first for
                    // dates and ratings, which is what jellyfin-web defaults to.
                    state.copy(sortBy = sortBy, sortOrder = sortBy.naturalOrder())
                }
            }
        }

        /** Flips ascending/descending without changing the sort key. */
        fun toggleSortOrder() {
            dropSelection()
            _uiState.update { it.copy(sortOrder = it.sortOrder.flipped()) }
        }

        /**
         * Ends selection mode because the grid is about to hold different items.
         *
         * Changing the sort or the filters swaps the `Pager` underneath, so the selection would
         * survive as a set of ids the user can no longer see — and the next batch action would then
         * act on items that are not on screen. Clearing is the honest answer, and it is what
         * jellyfin-web's own list re-queries do to a selection: nothing is silently retained.
         *
         * Called by the four mutators that change the query, and by nothing else — opening the
         * filter sheet or editing the draft filters re-queries nothing and keeps the selection.
         */
        private fun dropSelection() {
            _selection.update { it.cleared() }
        }

        /** Opens the filter sheet, loading the library's facets the first time it is needed. */
        fun openFilterSheet() {
            _uiState.update { it.copy(isFilterSheetOpen = true, draftFilters = it.filters) }
            val state = _uiState.value
            // Fetched once per screen — including when the server answered with nothing, which is
            // an answer, not a reason to ask again on every open.
            if (!state.areFacetsLoaded && !state.areFacetsLoading) {
                loadFacets()
            }
        }

        /** Closes the sheet, discarding any uncommitted edits. */
        fun dismissFilterSheet() {
            _uiState.update { it.copy(isFilterSheetOpen = false, draftFilters = it.filters) }
        }

        /** Records an edit inside the open sheet; nothing is re-queried until [applyFilters]. */
        fun updateDraftFilters(filters: FilterOptions) {
            _uiState.update { it.copy(draftFilters = filters) }
        }

        /** Commits the sheet's edits onto the grid, which re-queries the server. */
        fun applyFilters() {
            dropSelection()
            _uiState.update { it.copy(filters = it.draftFilters, isFilterSheetOpen = false) }
        }

        /** Drops every filter, from the sheet or from the empty state. */
        fun clearFilters() {
            dropSelection()
            _uiState.update {
                it.copy(
                    filters = FilterOptions(),
                    draftFilters = FilterOptions(),
                    isFilterSheetOpen = false,
                )
            }
        }

        /**
         * Everything the contextual selection bar can ask for (docs/features/batch-selection.md).
         *
         * [SelectionIntent.SelectAll] is deliberately a no-op here and the bar never offers it on
         * this screen: the grid is paged, so "all" would either mean "the pages loaded so far" — a
         * different set every time the user scrolls, with nothing on screen saying so — or "every
         * item matching the query", which no client-side call can enumerate without a new server
         * round trip per page. The season page, whose episode list is finite and fully loaded, does
         * offer it.
         */
        fun onSelection(intent: SelectionIntent) {
            when (intent) {
                is SelectionIntent.Toggle -> _selection.update { it.toggled(intent.itemId) }
                is SelectionIntent.Clear -> dropSelection()
                is SelectionIntent.SelectAll -> Unit
                is SelectionIntent.Run -> runSelection(intent.action)
            }
        }

        /** Clears the one-shot batch summary once the snackbar has shown it. */
        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        /**
         * Runs [action] over the selected ids, then reports one summary.
         *
         * Selection mode ends **before** the work starts, not after: the batch is composed of
         * ordinary single-item calls that can take a while (an enqueue is a full re-fetch per item),
         * and leaving the bar up over a live grid invites a second tap on the same selection. The
         * snackbar is what says when it finished.
         *
         * *Mark watched / unwatched* goes through `UserDataRepository`, which writes Room first and
         * publishes on the event bus before it contacts the server — so the whole batch works
         * offline, and the ticks appear from the local write.
         *
         * *Download* skips what is already on the device or already queued
         * ([DownloadState.isDownloadable]) and reports the count it skipped. A **series** never has
         * a download row of its own — the pipeline expands it into episodes — so it always looks
         * downloadable here and is always handed to `DownloadRepository.enqueue`, which does the
         * per-episode skipping itself (DECISIONS.md, 2026-07-29). Offline, an enqueue fails exactly
         * as a single tap on the same card would: the summary reports the failures.
         */
        private fun runSelection(action: SelectionAction) {
            val ids = _selection.value.ids.toList()
            if (ids.isEmpty()) return
            dropSelection()

            viewModelScope.launch {
                val outcome =
                    when (action) {
                        SelectionAction.MARK_WATCHED ->
                            runBatch(ids) { userDataRepository.setPlayed(it, played = true) }

                        SelectionAction.MARK_UNWATCHED ->
                            runBatch(ids) { userDataRepository.setPlayed(it, played = false) }

                        SelectionAction.DOWNLOAD -> {
                            val states = downloadStates.value
                            val targets =
                                ids.filter { (states[it] ?: DownloadState.NotDownloaded).isDownloadable }
                            runBatch(targets, skipped = ids.size - targets.size) { downloads.enqueue(it) }
                        }
                    }
                _uiState.update { it.copy(userMessage = BatchReport(action, outcome)) }
            }
        }

        /** Re-fetches the filter facets after a failure. */
        fun retryFacets() {
            loadFacets()
        }

        private fun loadFacets() {
            viewModelScope.launch {
                _uiState.update { it.copy(areFacetsLoading = true, facetsError = null) }
                val result =
                    repository.getFilterFacets(
                        parentId = libraryId,
                        itemTypes = LibraryUiState.GRID_ITEM_TYPES,
                    )
                _uiState.update { state ->
                    when (result) {
                        is AppResult.Success ->
                            state.copy(
                                areFacetsLoading = false,
                                areFacetsLoaded = true,
                                facets = result.value,
                                facetsError = null,
                            )

                        is AppResult.Failure ->
                            state.copy(areFacetsLoading = false, facetsError = result.error)
                    }
                }
            }
        }

        private companion object {
            /** `Routes.LibraryGrid` property names — Navigation stores type-safe args under these. */
            const val KEY_LIBRARY_ID = "libraryId"
            const val KEY_LIBRARY_NAME = "libraryName"
        }
    }

/** The opposite direction. */
internal fun SortOrder.flipped(): SortOrder =
    when (this) {
        SortOrder.ASCENDING -> SortOrder.DESCENDING
        SortOrder.DESCENDING -> SortOrder.ASCENDING
    }

/**
 * The direction a sort key reads best in: alphabetical keys ascend, everything with a "most
 * recent"/"highest" reading descends. Matches jellyfin-web's library defaults.
 */
internal fun SortBy.naturalOrder(): SortOrder =
    when (this) {
        SortBy.SORT_NAME, SortBy.RANDOM, SortBy.RUNTIME -> SortOrder.ASCENDING
        SortBy.DATE_CREATED, SortBy.PREMIERE_DATE, SortBy.COMMUNITY_RATING -> SortOrder.DESCENDING
    }
