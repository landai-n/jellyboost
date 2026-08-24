package dev.jellyboost.core.common.model

/**
 * The subset of Jellyfin `BaseItemKind` this client understands; anything else collapses into [UNKNOWN]
 * rather than leaking an SDK enum into the UI.
 */
enum class ItemType {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,

    AUDIO,

    MUSIC_ALBUM,

    MUSIC_ARTIST,

    /** View-only. */
    PLAYLIST,

    COLLECTION_FOLDER,
    FOLDER,
    UNKNOWN,
    ;

    val isPlayable: Boolean get() = this == MOVIE || this == EPISODE || this == AUDIO

    companion object {
        /**
         * Shared by `:feature:library`'s grid query and `:data`'s tile-count query — they must agree, or a
         * tile's count and the grid it opens disagree on what they are counting.
         */
        val LIBRARY_TILE_TYPES = listOf(MOVIE, SERIES)
    }
}
