package dev.jellyboost.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.selection.BatchReport
import dev.jellyboost.core.common.selection.ItemSelection
import dev.jellyboost.core.common.selection.SelectionAction
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.common.selection.runSelectionBatch
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.observeBadgeStates
import dev.jellyboost.data.downloads.withDownloadState
import dev.jellyboost.data.reloadOnChange
import dev.jellyboost.data.userdata.UserDataRepository
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
 * Three streams, deliberately separate: [items] leaves its loading/error/empty states to Paging's
 * `LoadState` (mirroring them into [uiState] is how a grid shows a spinner over loaded items),
 * [uiState] holds what the user can turn, and [selection] is apart so a cell reading it is not also
 * subscribed to sort and filters (docs/features/batch-selection.md).
 *
 * `distinctUntilChanged` keeps opening the sheet or editing the draft from re-issuing the query;
 * `cachedIn` keeps the loaded pages across configuration changes.
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

        val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        private val _selection = MutableStateFlow(ItemSelection())

        /**
         * Its own flow, not part of [uiState]: reading it out of [uiState] would subscribe every
         * visible cell to the sort key, the filters and the facets as well.
         */
        val selection: StateFlow<ItemSelection> = _selection.asStateFlow()

        /**
         * Collected once and shared with the batch *Download* action: `observeStates()` is a Room
         * Flow, and two collectors would be two queries re-running on every progress write.
         */
        private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

        /** Local user-data changes since this screen opened: a batch write shows on the cards with no refetch. */
        private val userDataPatches = MutableStateFlow<Map<String, UserData>>(emptyMap())

        /**
         * `cachedIn` comes **before** both combines on purpose: a badge or a watched tick re-maps the
         * loaded pages in place. A combine upstream of it would make every progress write a reload.
         */
        val items: Flow<PagingData<JellyfinItem>> =
            _uiState
                .map { it.toQuery(libraryId) }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    repository.getItemsPaged(query) { total -> publishTotalCount(query, total) }
                }.cachedIn(viewModelScope)
                .combine(downloadStates) { paging, states ->
                    paging.map { item -> item.withDownloadState(states) }
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

        /**
         * Stamped with the query it was counted for and dropped once that is no longer the query on
         * screen: a swapped-out `Pager` can still deliver its count afterwards, and labelling the new
         * filters with the old total is worse than showing nothing.
         */
        private fun publishTotalCount(
            query: ItemQuery,
            total: Int,
        ) {
            _uiState.update { state ->
                if (state.toQuery(libraryId) == query) state.copy(totalCount = total) else state
            }
        }

        init {
            observeConnectivityChanges()
            observeDownloadStates()
            observeUserDataChanges()
        }

        /**
         * Mirrored into a `MutableStateFlow` rather than combined straight into [items] because the
         * batch *Download* action reads the same map synchronously.
         */
        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads.observeBadgeStates(screen = "library").collect { states ->
                    downloadStates.value = states
                }
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
         * Reloads **only the facets**: `getItemsPaged` rebuilds its `Pager` on every connection
         * change, so the items swap source on their own. And only when the facets were already asked
         * for — a sheet that was never opened has nothing stale to replace.
         */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(
                viewModelScope,
                onlyIf = {
                    val state = _uiState.value
                    val wasAsked = state.areFacetsLoaded || state.facetsError != null
                    wasAsked && !state.areFacetsLoading
                },
            ) { retryFacets() }
        }

        fun selectSort(sortBy: SortBy) {
            dropSelection()
            _uiState.update { state ->
                if (state.sortBy == sortBy) {
                    state.copy(sortOrder = state.sortOrder.flipped())
                } else {
                    // A fresh key starts in its natural direction, which is what jellyfin-web defaults to.
                    state.copy(sortBy = sortBy, sortOrder = sortBy.naturalOrder())
                }
            }
        }

        fun toggleSortOrder() {
            dropSelection()
            _uiState.update { it.copy(sortOrder = it.sortOrder.flipped()) }
        }

        /**
         * Ends selection mode because the grid is about to hold different items: the set would
         * otherwise survive as ids the user can no longer see, and the next batch action would act on
         * them. Called by the four mutators that change the query and by nothing else.
         */
        private fun dropSelection() {
            _selection.update { it.cleared() }
        }

        fun openFilterSheet() {
            _uiState.update { it.copy(isFilterSheetOpen = true, draftFilters = it.filters) }
            val state = _uiState.value
            // Fetched once per screen, including when the server answered with nothing — that is an answer.
            if (!state.areFacetsLoaded && !state.areFacetsLoading) {
                loadFacets()
            }
        }

        fun dismissFilterSheet() {
            _uiState.update { it.copy(isFilterSheetOpen = false, draftFilters = it.filters) }
        }

        fun updateDraftFilters(filters: FilterOptions) {
            _uiState.update { it.copy(draftFilters = filters) }
        }

        fun applyFilters() {
            dropSelection()
            _uiState.update {
                it.copy(filters = it.draftFilters, isFilterSheetOpen = false, totalCount = null)
            }
        }

        fun clearFilters() {
            dropSelection()
            _uiState.update {
                it.copy(
                    filters = FilterOptions(),
                    draftFilters = FilterOptions(),
                    isFilterSheetOpen = false,
                    totalCount = null,
                )
            }
        }

        /**
         * The row has no draft stage — a chip *is* the applied state — so it commits straight onto
         * [LibraryUiState.filters], keeping the draft in step so opening the sheet does not revert it.
         */
        fun toggleFilterChip(chip: LibraryFilterChip) {
            dropSelection()
            _uiState.update { state ->
                val next = state.filters.toggled(chip)
                state.copy(filters = next, draftFilters = next, totalCount = null)
            }
        }

        /**
         * [SelectionIntent.SelectAll] is deliberately a no-op and the bar never offers it here: the
         * grid is paged, so "all" would mean either the pages loaded so far or a server round trip per
         * page. The season page, whose episode list is finite, does offer it.
         */
        fun onSelection(intent: SelectionIntent) {
            when (intent) {
                is SelectionIntent.Toggle -> _selection.update { it.toggled(intent.itemId) }
                is SelectionIntent.Clear -> dropSelection()
                is SelectionIntent.SelectAll -> Unit
                is SelectionIntent.Run -> runSelection(intent.action)
            }
        }

        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        /**
         * Selection mode ends **before** the work starts: the batch is ordinary single-item calls
         * that can take a while, and leaving the bar up over a live grid invites a second tap on the
         * same selection. Shared with the detail screen's episode list via [runSelectionBatch].
         */
        private fun runSelection(action: SelectionAction) {
            val ids = _selection.value.ids.toList()
            if (ids.isEmpty()) return
            dropSelection()

            viewModelScope.launch {
                val outcome =
                    runSelectionBatch(
                        action = action,
                        ids = ids,
                        downloadStates = downloadStates.value,
                        setPlayed = userDataRepository::setPlayed,
                        enqueue = downloads::enqueue,
                    )
                _uiState.update { it.copy(userMessage = BatchReport(action, outcome)) }
            }
        }

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
            const val KEY_LIBRARY_ID = "libraryId"
            const val KEY_LIBRARY_NAME = "libraryName"
        }
    }

internal fun SortOrder.flipped(): SortOrder =
    when (this) {
        SortOrder.ASCENDING -> SortOrder.DESCENDING
        SortOrder.DESCENDING -> SortOrder.ASCENDING
    }

/** Alphabetical keys ascend, "most recent"/"highest" keys descend — jellyfin-web's defaults. */
internal fun SortBy.naturalOrder(): SortOrder =
    when (this) {
        SortBy.SORT_NAME, SortBy.RANDOM, SortBy.RUNTIME -> SortOrder.ASCENDING
        SortBy.DATE_CREATED, SortBy.PREMIERE_DATE, SortBy.COMMUNITY_RATING -> SortOrder.DESCENDING
    }
