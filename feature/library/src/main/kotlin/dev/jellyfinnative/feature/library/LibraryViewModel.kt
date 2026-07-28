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
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
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
 * Two streams, deliberately separate:
 * - [items] — the paged content. Its loading, error and empty states belong to Paging's
 *   `LoadState`, so this class never mirrors them into [uiState]; duplicating them is how a grid
 *   ends up showing a spinner over already-loaded items.
 * - [uiState] — everything the user can turn (sort, filters) plus the filter sheet.
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
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val libraryId: String = savedStateHandle.get<String>(KEY_LIBRARY_ID).orEmpty()

        private val _uiState =
            MutableStateFlow(
                LibraryUiState(libraryName = savedStateHandle.get<String>(KEY_LIBRARY_NAME).orEmpty()),
            )

        /** Sort, filters and sheet state. */
        val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        /**
         * The grid's paged content, with each card's download badge stamped on.
         *
         * `cachedIn` comes **before** the download-state combine on purpose: it caches the pages, so
         * a badge changing re-maps the already-loaded pages in place instead of re-fetching them
         * from the server. Putting the combine upstream of `cachedIn` would make every progress
         * write a full reload of the grid.
         */
        val items: Flow<PagingData<JellyfinItem>> =
            _uiState
                .map { it.toQuery(libraryId) }
                .distinctUntilChanged()
                .flatMapLatest { repository.getItemsPaged(it) }
                .cachedIn(viewModelScope)
                .combine(downloads.observeStates()) { paging, states ->
                    paging.map { item ->
                        val next = states[item.id] ?: DownloadState.NotDownloaded
                        if (next == item.downloadState) item else item.copy(downloadState = next)
                    }
                }

        /** Applies a sort key; picking the key that is already active flips the direction. */
        fun selectSort(sortBy: SortBy) {
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
            _uiState.update { it.copy(sortOrder = it.sortOrder.flipped()) }
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
            _uiState.update { it.copy(filters = it.draftFilters, isFilterSheetOpen = false) }
        }

        /** Drops every filter, from the sheet or from the empty state. */
        fun clearFilters() {
            _uiState.update {
                it.copy(
                    filters = FilterOptions(),
                    draftFilters = FilterOptions(),
                    isFilterSheetOpen = false,
                )
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
