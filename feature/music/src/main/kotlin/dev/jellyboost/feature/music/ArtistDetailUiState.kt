package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * Everything [ArtistDetailScreen] draws.
 *
 * [errorMessage] carries the raw [AppError] — see [AlbumDetailUiState]'s KDoc for why.
 */
data class ArtistDetailUiState(
    val isLoading: Boolean = true,
    val artist: JellyfinItem? = null,
    val albums: List<JellyfinItem> = emptyList(),
    val topTracks: List<JellyfinItem> = emptyList(),
    val topTracksExpanded: Boolean = false,
    val errorMessage: AppError? = null,
) {
    /** The top tracks actually drawn: five, or all of them once expanded. */
    val visibleTopTracks: List<JellyfinItem>
        get() = if (topTracksExpanded) topTracks else topTracks.take(COLLAPSED_TOP_TRACKS)

    /** Whether the "show more" affordance has anything to reveal. */
    val hasMoreTopTracks: Boolean
        get() = topTracks.size > COLLAPSED_TOP_TRACKS

    companion object {
        /** Rows shown before "show more" — the milestone plan's "Top tracks (5, expandable)". */
        const val COLLAPSED_TOP_TRACKS = 5
    }
}

/**
 * Stamps the app-wide download-state map onto the artist's albums and top tracks (M13 Phase 5).
 *
 * Both halves, not just the tracks: an album card carries the same `DownloadBadge` every other card
 * in the app does, and the artist page is where a user checks what of an artist they already have
 * offline.
 */
internal fun ArtistDetailUiState.withDownloadStates(states: Map<String, DownloadState>): ArtistDetailUiState =
    copy(
        albums = albums.map { it.withDownloadState(states) },
        topTracks = topTracks.map { it.withDownloadState(states) },
    )

/** This item carrying its current download state, or itself when nothing changed. */
internal fun JellyfinItem.withDownloadState(states: Map<String, DownloadState>): JellyfinItem {
    val next = states[id] ?: DownloadState.NotDownloaded
    return if (next == downloadState) this else copy(downloadState = next)
}
