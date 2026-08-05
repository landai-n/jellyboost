package dev.jellyboost.core.common.model

/**
 * A navigable reference to a music artist.
 *
 * A track or album's `artists`/`albumArtist` fields on [JellyfinItem] are display strings only —
 * tapping one to open the artist's page needs an id, which the server carries separately on
 * `dto.artistItems`/`dto.albumArtists` (docs/notes/music-m13-plan.md, decision 5).
 */
data class ArtistRef(
    val id: String,
    val name: String,
)
