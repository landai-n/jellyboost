package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * Everything [PlaylistDetailScreen] draws.
 *
 * [errorMessage] carries the raw [AppError] — see [AlbumDetailUiState]'s KDoc for why.
 */
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val playlist: JellyfinItem? = null,
    val tracks: List<JellyfinItem> = emptyList(),
    val errorMessage: AppError? = null,
)

/**
 * Stamps the app-wide download-state map onto the playlist's tracks (M13 Phase 5).
 *
 * The badges are honest here even though the playlist itself has no offline model: a track's badge
 * describes *that track's* file on this device, which is exactly what a playlist download produces
 * — the tracks land under their own albums, and this list is only ever drawn online.
 */
internal fun PlaylistDetailUiState.withDownloadStates(states: Map<String, DownloadState>): PlaylistDetailUiState =
    copy(tracks = tracks.map { it.withDownloadState(states) })
