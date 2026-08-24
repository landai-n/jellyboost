package dev.jellyboost.core.common.model

/**
 * What the server *has*, where [FilterOptions] is what the user *picked*. Fetched once per library when the
 * filter sheet is first opened.
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
