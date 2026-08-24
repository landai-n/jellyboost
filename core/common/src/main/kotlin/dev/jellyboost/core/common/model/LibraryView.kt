package dev.jellyboost.core.common.model

/**
 * @param itemCount how many *titles* the library holds, counted recursively. Deliberately **not** the
 *   server's `ChildCount` on the collection folder: that counts the folder's direct children — the library's
 *   media folders — so a 177-movie library reports 3. Only a recursive item query can answer it, so it is
 *   `null` for any library rebuilt from the offline cache or whose count request failed; tiles hide the line.
 */
data class LibraryView(
    val id: String,
    val name: String,
    val collectionType: CollectionKind,
    val primaryImageUrl: String? = null,
    val thumbImageUrl: String? = null,
    val itemCount: Int? = null,
)

enum class CollectionKind {
    MOVIES,
    TVSHOWS,

    MUSIC,

    /** Live TV, photos, … — recognised but not shown. */
    OTHER,
    ;

    companion object {
        val SUPPORTED: Set<CollectionKind> = setOf(MOVIES, TVSHOWS, MUSIC)
    }
}
