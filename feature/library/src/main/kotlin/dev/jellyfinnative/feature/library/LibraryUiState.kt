package dev.jellyfinnative.feature.library

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.FilterOptions
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.SortBy
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.core.common.selection.BatchReport

/**
 * Everything the library grid draws around its paged items.
 *
 * The items themselves are *not* here: they arrive as a `PagingData` flow, whose loading and error
 * states Paging owns (see [LibraryViewModel.items]). This state holds only what the user can turn:
 * sort, filters, and the sheet that edits them.
 */
data class LibraryUiState(
    val libraryName: String = "",
    val sortBy: SortBy = SortBy.SORT_NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    /** Filters currently applied to the grid. */
    val filters: FilterOptions = FilterOptions(),
    /** Filters being edited in the sheet; committed onto [filters] when the user applies them. */
    val draftFilters: FilterOptions = FilterOptions(),
    /** What this library can be filtered by, fetched the first time the sheet opens. */
    val facets: FilterFacets = FilterFacets(),
    /** `true` once the facets came back — including when they came back empty. */
    val areFacetsLoaded: Boolean = false,
    val isFilterSheetOpen: Boolean = false,
    val areFacetsLoading: Boolean = false,
    val facetsError: AppError? = null,
    /**
     * A finished batch action, waiting for the snackbar; cleared by
     * [LibraryViewModel.consumeMessage].
     *
     * The *selection* itself is deliberately **not** here — it lives in
     * [LibraryViewModel.selection], its own `StateFlow`. A grid cell that had to read this state
     * class to know whether it is selected would also be subscribed to the sort key, the filters,
     * the facets and this message, and would recompose whenever any of them changed.
     */
    val userMessage: BatchReport? = null,
) {
    /** Number of active filter facets, for the "2" badge on the filter action. */
    val activeFilterCount: Int
        get() = filters.activeCount

    /** The server query the grid is currently paging over. */
    fun toQuery(libraryId: String): ItemQuery =
        ItemQuery(
            parentId = libraryId,
            itemTypes = GRID_ITEM_TYPES,
            recursive = true,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = filters,
        )

    companion object {
        /**
         * The grid lists top-level titles, whatever kind of library it is: a movie library answers
         * with movies and a TV library with series, so one type list serves both and the route does
         * not have to carry the collection kind.
         */
        val GRID_ITEM_TYPES = listOf(ItemType.MOVIE, ItemType.SERIES)
    }
}

/** The sort keys the grid's menu offers, in the order it lists them. */
val LIBRARY_SORT_OPTIONS: List<SortBy> =
    listOf(
        SortBy.SORT_NAME,
        SortBy.DATE_CREATED,
        SortBy.PREMIERE_DATE,
        SortBy.COMMUNITY_RATING,
        SortBy.RUNTIME,
        SortBy.RANDOM,
    )
