package dev.jellyfinnative.core.common.model

/**
 * User-selected filters for a library grid or a search.
 *
 * Consumed by the paged library query in M3; kept here so that the online and offline query
 * paths take the exact same filter description.
 */
data class FilterOptions(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    /** `true` = only watched, `false` = only unwatched, `null` = no filter. */
    val isPlayed: Boolean? = null,
    /** `true` = favourites only, `null` = no filter. */
    val isFavorite: Boolean? = null,
) {
    /** `true` when nothing is filtered — lets callers skip the filter round-trip entirely. */
    val isEmpty: Boolean
        get() =
            genres.isEmpty() &&
                years.isEmpty() &&
                officialRatings.isEmpty() &&
                isPlayed == null &&
                isFavorite == null

    /** Number of active filter facets, for the "Filters (2)" chip. */
    val activeCount: Int
        get() =
            listOf(
                genres.isNotEmpty(),
                years.isNotEmpty(),
                officialRatings.isNotEmpty(),
                isPlayed != null,
                isFavorite != null,
            ).count { it }
}
