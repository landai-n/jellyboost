package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/** [errorMessage] carries the raw [AppError] — see [AlbumDetailUiState]. */
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val playlist: JellyfinItem? = null,
    val tracks: List<JellyfinItem> = emptyList(),
    val errorMessage: AppError? = null,
)

/**
 * Honest even though a playlist has no offline model: a track's badge describes *that track's* file
 * on this device, and a playlist download lands its tracks under their own albums.
 */
internal fun PlaylistDetailUiState.withDownloadStates(states: Map<String, DownloadState>): PlaylistDetailUiState =
    copy(tracks = tracks.map { it.withDownloadState(states) })
