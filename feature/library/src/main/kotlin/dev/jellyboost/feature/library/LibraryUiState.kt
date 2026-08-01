package dev.jellyboost.feature.library

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.selection.BatchReport

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
    /**
     * How many items match the current query, as the server reported it on the grid's first page —
     * the header's "N items" line.
     *
     * `null` means "not known", which is the state before the first page lands, offline (Room holds
     * the downloaded items, not the library) and whenever the query changes: the number belongs to
     * one set of filters and re-labelling the previous count as this query's would be a lie for as
     * long as the new first page takes to arrive.
     */
    val totalCount: Int? = null,
) {
    /** Number of active filter facets, for the "2" badge on the filter action. */
    val activeFilterCount: Int
        get() = filters.activeCount

    /**
     * The chips the grid's inline filter row offers, in the order it draws them.
     *
     * Two kinds, and no new filter semantics: the *watched* toggles are always available, because
     * they need nothing from the server, and every genre or year currently applied appears as a
     * selected chip so it can be dropped with one tap. Facets the user has **not** applied are
     * deliberately absent — they arrive only once the filter sheet has been opened
     * ([LibraryViewModel.openFilterSheet] fetches them), and a row that silently grew a dozen
     * genres after an unrelated interaction reads as a bug. The sheet remains the full editor.
     */
    val filterChips: List<LibraryFilterChip>
        get() =
            buildList {
                add(LibraryFilterChip.Unwatched)
                add(LibraryFilterChip.Watched)
                filters.genres.forEach { add(LibraryFilterChip.Genre(it)) }
                filters.years.forEach { add(LibraryFilterChip.Year(it)) }
            }

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

/**
 * One chip in the grid's inline filter row, and the [FilterOptions] edit it stands for.
 *
 * A chip is a *shortcut into the existing filter model*, never a new kind of filter: each case maps
 * onto a field the filter sheet already edits, so a chip and the sheet cannot disagree about what
 * is applied ([toggled], [isApplied]).
 */
sealed interface LibraryFilterChip {
    /** `isPlayed = false` — only what has not been watched. */
    data object Unwatched : LibraryFilterChip

    /** `isPlayed = true` — only what has. */
    data object Watched : LibraryFilterChip

    /** One applied genre, so it can be dropped without opening the sheet. */
    data class Genre(
        val name: String,
    ) : LibraryFilterChip

    /** One applied year, likewise. */
    data class Year(
        val value: Int,
    ) : LibraryFilterChip
}

/** Whether [chip]'s filter is part of these options — the chip's selected state. */
fun FilterOptions.isApplied(chip: LibraryFilterChip): Boolean =
    when (chip) {
        LibraryFilterChip.Unwatched -> isPlayed == false
        LibraryFilterChip.Watched -> isPlayed == true
        is LibraryFilterChip.Genre -> chip.name in genres
        is LibraryFilterChip.Year -> chip.value in years
    }

/**
 * These options with [chip] turned on if it was off, and off if it was on.
 *
 * The two watched chips share one tri-state field, so turning either on turns the other off — the
 * same exclusivity the sheet's *Any / Watched / Unwatched* row expresses with three buttons.
 */
fun FilterOptions.toggled(chip: LibraryFilterChip): FilterOptions =
    when (chip) {
        LibraryFilterChip.Unwatched -> copy(isPlayed = if (isPlayed == false) null else false)
        LibraryFilterChip.Watched -> copy(isPlayed = if (isPlayed == true) null else true)
        is LibraryFilterChip.Genre -> copy(genres = genres.toggle(chip.name))
        is LibraryFilterChip.Year -> copy(years = years.toggle(chip.value))
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
