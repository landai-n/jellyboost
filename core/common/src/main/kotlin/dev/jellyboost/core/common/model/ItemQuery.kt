package dev.jellyboost.core.common.model

/**
 * A request for a page of items, expressed in domain terms.
 *
 * The online repository translates this into the SDK's `getItems` parameters and the offline
 * repository into a Room query, so paging behaves identically in both modes (docs/PLAN.md,
 * "Screens" → LibraryGrid). M2 only needs the home rows; the paged library grid consumes this
 * in M3.
 */
data class ItemQuery(
    val parentId: String? = null,
    val itemTypes: List<ItemType> = emptyList(),
    val searchTerm: String? = null,
    val recursive: Boolean = true,
    val sortBy: SortBy = SortBy.SORT_NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val filters: FilterOptions = FilterOptions(),
    val startIndex: Int = 0,
    val limit: Int = DEFAULT_PAGE_SIZE,
    /**
     * Whether the server should also report how many items match — the "N items" line in the
     * library grid's header.
     *
     * Off by default, and set by the paging source on the **first** page of a grid only: the total
     * costs the server a `COUNT` over the whole query, and nothing else in the app needs it (the
     * end of a paged list is detected by a short page, not by a total). See DECISIONS.md
     * 2026-08-01, "the library grid's first page asks for the total record count".
     */
    val includeTotalCount: Boolean = false,
) {
    init {
        require(startIndex >= 0) { "startIndex must not be negative, was $startIndex" }
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    companion object {
        /** Page size used by the library grid — one server request per screenful. */
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/** Sort keys offered by the library grid. */
enum class SortBy {
    SORT_NAME,
    DATE_CREATED,
    PREMIERE_DATE,
    COMMUNITY_RATING,
    RUNTIME,
    RANDOM,
}

/** Sort direction. */
enum class SortOrder {
    ASCENDING,
    DESCENDING,
}
