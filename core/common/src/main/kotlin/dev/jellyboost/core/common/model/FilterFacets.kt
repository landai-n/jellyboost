package dev.jellyboost.core.common.model

/**
 * The set of values a library can actually be filtered by — what the filter sheet offers.
 *
 * Counterpart of [FilterOptions]: this is what the server *has*, [FilterOptions] is what the user
 * *picked*. Fetched once per library when the sheet is first opened (docs/PLAN.md, "Screens" →
 * LibraryGrid, "filter sheet `getQueryFilters`").
 */
data class FilterFacets(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
) {
    /** `true` when the server offered nothing to filter by — the sheet then shows only played state. */
    val isEmpty: Boolean
        get() = genres.isEmpty() && years.isEmpty() && officialRatings.isEmpty()
}
