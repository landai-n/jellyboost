package dev.jellyfinnative.core.common.model

/**
 * A user library ("Movies", "Shows") as shown in the home screen's *My Media* row and in the
 * Libraries tab.
 */
data class LibraryView(
    val id: String,
    val name: String,
    val collectionType: CollectionKind,
    val primaryImageUrl: String? = null,
    val thumbImageUrl: String? = null,
)

/**
 * Library kinds this client supports.
 *
 * v1 is movies and TV shows only, so `getUserViews` results are filtered down to [MOVIES] and
 * [TVSHOWS] (docs/PLAN.md, "Screens" → Home).
 */
enum class CollectionKind {
    MOVIES,
    TVSHOWS,

    /** Music, live TV, photos, … — recognised but not shown in v1. */
    OTHER,
    ;

    companion object {
        /** The kinds v1 surfaces to the user. */
        val SUPPORTED: Set<CollectionKind> = setOf(MOVIES, TVSHOWS)
    }
}
