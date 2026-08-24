package dev.jellyboost.core.common.model

/**
 * A user library ("Movies", "Shows") as shown in the home screen's *My Media* row and in the
 * Libraries tab.
 *
 * @param itemCount how many *titles* the library holds — the tile's "412 items" subtitle, and the
 *   same number the library grid's own header reports (movies and series, counted recursively).
 *   Deliberately **not** the server's `ChildCount` on the collection folder: that field counts the
 *   folder's direct children — the library's media folders — so a 177-movie library reports 3. Only
 *   a recursive item query can answer this, so it is `null` whenever one was not made or did not
 *   succeed: every library rebuilt from the offline cache (the count is not stored in Room, and a
 *   cache holding only the downloaded items could not answer it honestly anyway) and any library
 *   whose count request failed. Tiles simply hide the line then.
 */
data class LibraryView(
    val id: String,
    val name: String,
    val collectionType: CollectionKind,
    val primaryImageUrl: String? = null,
    val thumbImageUrl: String? = null,
    val itemCount: Int? = null,
)

/**
 * Library kinds this client supports.
 *
 * Movies and TV shows are the core kinds, so `getUserViews` results are filtered down to [MOVIES]
 * and [TVSHOWS].
 */
enum class CollectionKind {
    MOVIES,
    TVSHOWS,

    /** A music library, alongside `:feature:music` — the UI it opens onto. */
    MUSIC,

    /** Live TV, photos, … — recognised but not shown in v1. */
    OTHER,
    ;

    companion object {
        /** The kinds the app surfaces to the user. */
        val SUPPORTED: Set<CollectionKind> = setOf(MOVIES, TVSHOWS, MUSIC)
    }
}
