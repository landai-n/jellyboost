package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
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
