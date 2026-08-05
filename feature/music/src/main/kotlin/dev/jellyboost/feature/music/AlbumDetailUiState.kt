package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * Everything [AlbumDetailScreen] draws.
 *
 * [errorMessage] carries the raw [AppError] rather than already-formatted copy — `toMessage()` is
 * `@Composable` (it reads string resources), so translating it happens at render time in the
 * screen, the same split `SearchUiState`/`LibraryUiState` use.
 */
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val album: JellyfinItem? = null,
    val tracks: List<JellyfinItem> = emptyList(),
    val errorMessage: AppError? = null,
) {
    /**
     * `true` once the tracks name more than one disc — the point at which the list needs "Disc N"
     * headers to make sense of the numbering starting over (docs/notes/music-m13-plan.md, Phase 2:
     * "track list grouped by disc when parentIndexNumber varies").
     */
    val isMultiDisc: Boolean
        get() = tracks.mapNotNull { it.parentIndexNumber }.distinct().size > 1

    /** Tracks in server order, grouped by disc number (missing numbers fall under disc 1). */
    val tracksByDisc: List<Pair<Int, List<JellyfinItem>>>
        get() =
            tracks
                .groupBy { it.parentIndexNumber ?: 1 }
                .toSortedMap()
                .map { (disc, discTracks) -> disc to discTracks }
}

/** Stamps the app-wide download-state map onto every track (M7 badge pattern). */
internal fun AlbumDetailUiState.withDownloadStates(states: Map<String, DownloadState>): AlbumDetailUiState =
    copy(
        tracks =
            tracks.map { track ->
                val next = states[track.id] ?: DownloadState.NotDownloaded
                if (next == track.downloadState) track else track.copy(downloadState = next)
            },
    )
