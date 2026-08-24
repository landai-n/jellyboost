package dev.jellyboost.core.common.model

/**
 * [JellyfinItem]'s `artists`/`albumArtist` are display strings only; tapping through to an artist page needs
 * an id, which the server carries separately on `dto.artistItems`/`dto.albumArtists`.
 */
data class ArtistRef(
    val id: String,
    val name: String,
)
