package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/** [errorMessage] carries the raw [AppError] — see [AlbumDetailUiState]. */
data class ArtistDetailUiState(
    val isLoading: Boolean = true,
    val artist: JellyfinItem? = null,
    val albums: List<JellyfinItem> = emptyList(),
    val topTracks: List<JellyfinItem> = emptyList(),
    val topTracksExpanded: Boolean = false,
    val errorMessage: AppError? = null,
) {
    val visibleTopTracks: List<JellyfinItem>
        get() = if (topTracksExpanded) topTracks else topTracks.take(COLLAPSED_TOP_TRACKS)

    val hasMoreTopTracks: Boolean
        get() = topTracks.size > COLLAPSED_TOP_TRACKS

    companion object {
        const val COLLAPSED_TOP_TRACKS = 5
    }
}

/** Both halves, not just the tracks: an album card carries a `DownloadBadge` too. */
internal fun ArtistDetailUiState.withDownloadStates(states: Map<String, DownloadState>): ArtistDetailUiState =
    copy(
        albums = albums.map { it.withDownloadState(states) },
        topTracks = topTracks.map { it.withDownloadState(states) },
    )

internal fun JellyfinItem.withDownloadState(states: Map<String, DownloadState>): JellyfinItem {
    val next = states[id] ?: DownloadState.NotDownloaded
    return if (next == downloadState) this else copy(downloadState = next)
}
