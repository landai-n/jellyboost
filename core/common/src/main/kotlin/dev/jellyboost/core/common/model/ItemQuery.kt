package dev.jellyboost.core.common.model

/**
 * The online repository translates this into the SDK's `getItems` parameters and the offline one into a Room
 * query, so paging behaves identically in both modes.
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
     * Set by the paging source on the **first** page only: the total costs the server a `COUNT` over the whole
     * query, and the end of a paged list is detected by a short page, not by a total.
     */
    val includeTotalCount: Boolean = false,
) {
    init {
        require(startIndex >= 0) { "startIndex must not be negative, was $startIndex" }
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

enum class SortBy {
    SORT_NAME,
    DATE_CREATED,
    PREMIERE_DATE,
    COMMUNITY_RATING,
    RUNTIME,
    RANDOM,
}

enum class SortOrder {
    ASCENDING,
    DESCENDING,
}
