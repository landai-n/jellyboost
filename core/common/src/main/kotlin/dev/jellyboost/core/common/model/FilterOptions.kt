package dev.jellyboost.core.common.model

/** Kept here so the online and offline query paths take the exact same filter description. */
data class FilterOptions(
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    /** `true` = only watched, `false` = only unwatched, `null` = no filter. */
    val isPlayed: Boolean? = null,
    /** `true` = favourites only, `null` = no filter. */
    val isFavorite: Boolean? = null,
) {
    val isEmpty: Boolean
        get() =
            genres.isEmpty() &&
                years.isEmpty() &&
                officialRatings.isEmpty() &&
                isPlayed == null &&
                isFavorite == null

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
