package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
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
