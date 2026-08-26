package dev.jellyboost.core.common

import kotlinx.serialization.Serializable

/**
 * Type-safe routes. They live in `:core:common` so feature modules can navigate to each other without
 * ever depending on each other.
 */
object Routes {
    @Serializable
    data object Home

    @Serializable
    data object Libraries

    @Serializable
    data object Search

    @Serializable
    data object Downloads

    @Serializable
    data object Settings

    /** The app's own licence, verbatim — GPL-3.0 §4 requires the binary to convey it. */
    @Serializable
    data object Licence

    @Serializable
    data object ThirdPartyLicences

    @Serializable
    data object ServerSetup

    @Serializable
    data object Login

    @Serializable
    data object SyncPlay

    @Serializable
    data class Library(
        val libraryId: String,
    )

    @Serializable
    data class ItemDetail(
        val itemId: String,
    )

    /**
     * @param mediaSourceId `null` lets the server pick the default one (see `PlaybackInfoResolver`'s dash-less
     *   media-source-id quirk).
     * @param startPositionTicks in Jellyfin ticks; `0` for Play from the beginning.
     */
    @Serializable
    data class Player(
        val itemId: String,
        val mediaSourceId: String? = null,
        val startPositionTicks: Long = 0L,
    )

    // Only the grid needs a route of its own: it carries the library name for its top bar, and a second
    // `getUserViews` round trip just to render a title would be wasteful.

    @Serializable
    data class LibraryGrid(
        val libraryId: String,
        val libraryName: String,
    )

    @Serializable
    data class MusicLibrary(
        val libraryId: String,
        val libraryName: String,
    )

    @Serializable
    data class AlbumDetail(
        val albumId: String,
    )

    @Serializable
    data class ArtistDetail(
        val artistId: String,
    )

    /** View-only — playlist *editing* is not implemented. */
    @Serializable
    data class PlaylistDetail(
        val playlistId: String,
    )

    /**
     * No arguments — unlike [Player], this is a *view* onto the one process-wide
     * [dev.jellyboost.core.common.music.MusicController.state], not something opened for a particular item.
     */
    @Serializable
    data object NowPlaying
}
