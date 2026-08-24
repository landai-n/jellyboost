package dev.jellyboost.feature.library

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.selection.BatchReport

/** The items themselves are not here: they arrive as a `PagingData` flow that owns its own states. */
data class LibraryUiState(
    val libraryName: String = "",
    val sortBy: SortBy = SortBy.SORT_NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val filters: FilterOptions = FilterOptions(),
    val draftFilters: FilterOptions = FilterOptions(),
    val facets: FilterFacets = FilterFacets(),
    /** `true` once the facets came back — including when they came back empty. */
    val areFacetsLoaded: Boolean = false,
    val isFilterSheetOpen: Boolean = false,
    val areFacetsLoading: Boolean = false,
    val facetsError: AppError? = null,
    /**
     * The *selection* is deliberately not here but in [LibraryViewModel.selection]: a cell reading
     * this class would also be subscribed to sort, filters, facets and this message.
     */
    val userMessage: BatchReport? = null,
    /**
     * `null` means "not known": before the first page lands, offline, and whenever the query
     * changes — the count belongs to one set of filters and must not be re-labelled as another's.
     */
    val totalCount: Int? = null,
) {
    val activeFilterCount: Int
        get() = filters.activeCount

    /**
     * Facets the user has **not** applied are deliberately absent: they arrive only once the sheet
     * has been opened, and a row that silently grew a dozen genres reads as a bug.
     *
     * Built in the constructor body, not a `get()`: a fresh list on every read is a list the
     * `LazyRow` can never skip.
     */
    val filterChips: List<LibraryFilterChip> =
        buildList {
            add(LibraryFilterChip.Unwatched)
            add(LibraryFilterChip.Watched)
            filters.genres.forEach { add(LibraryFilterChip.Genre(it)) }
            filters.years.forEach { add(LibraryFilterChip.Year(it)) }
        }

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
         * One type list serves both movie and TV libraries, so the route need not carry the
         * collection kind. [ItemType.LIBRARY_TILE_TYPES] is shared with `:data`'s tile-count query,
         * which cannot depend on this module.
         */
        val GRID_ITEM_TYPES = ItemType.LIBRARY_TILE_TYPES
    }
}

/** A chip is a shortcut into the existing [FilterOptions], never a new kind of filter. */
sealed interface LibraryFilterChip {
    data object Unwatched : LibraryFilterChip

    data object Watched : LibraryFilterChip

    data class Genre(
        val name: String,
    ) : LibraryFilterChip

    data class Year(
        val value: Int,
    ) : LibraryFilterChip
}

fun FilterOptions.isApplied(chip: LibraryFilterChip): Boolean =
    when (chip) {
        LibraryFilterChip.Unwatched -> isPlayed == false
        LibraryFilterChip.Watched -> isPlayed == true
        is LibraryFilterChip.Genre -> chip.name in genres
        is LibraryFilterChip.Year -> chip.value in years
    }

/** The two watched chips share one tri-state field, so turning either on turns the other off. */
fun FilterOptions.toggled(chip: LibraryFilterChip): FilterOptions =
    when (chip) {
        LibraryFilterChip.Unwatched -> copy(isPlayed = if (isPlayed == false) null else false)
        LibraryFilterChip.Watched -> copy(isPlayed = if (isPlayed == true) null else true)
        is LibraryFilterChip.Genre -> copy(genres = genres.toggle(chip.name))
        is LibraryFilterChip.Year -> copy(years = years.toggle(chip.value))
    }

val LIBRARY_SORT_OPTIONS: List<SortBy> =
    listOf(
        SortBy.SORT_NAME,
        SortBy.DATE_CREATED,
        SortBy.PREMIERE_DATE,
        SortBy.COMMUNITY_RATING,
        SortBy.RUNTIME,
        SortBy.RANDOM,
    )
