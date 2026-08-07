package dev.jellyboost.core.common.model

/**
 * The subset of Jellyfin `BaseItemKind` values this client understands.
 *
 * v1 scope is movies and TV shows (docs/PLAN.md, "Confirmed decisions"), so anything else the
 * server sends collapses into [UNKNOWN] rather than leaking an SDK enum into the UI.
 */
enum class ItemType {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,

    /** A user library ("Movies", "Shows") — the parent of everything else. */
    COLLECTION_FOLDER,
    FOLDER,
    UNKNOWN,
    ;

    /** `true` for the types that can actually be played back. */
    val isPlayable: Boolean get() = this == MOVIE || this == EPISODE

    companion object {
        /**
         * The item kinds a library tile leads to, whatever kind of library it is: a movie library
         * answers with movies and a TV library with series, so one list serves both (DUP-11).
         *
         * Shared by `:feature:library`'s grid query and `:data`'s tile-count query — they must
         * agree, or a tile's count and the grid it opens disagree on what they are counting.
         */
        val LIBRARY_TILE_TYPES = listOf(MOVIE, SERIES)
    }
}
