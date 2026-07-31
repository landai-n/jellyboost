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
}
