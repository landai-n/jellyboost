package dev.jellyboost.feature.music

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * [errorMessage] carries the raw [AppError], not formatted copy: `toMessage()` is `@Composable`, so
 * it can only be resolved at render time.
 */
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val album: JellyfinItem? = null,
    val tracks: List<JellyfinItem> = emptyList(),
    val errorMessage: AppError? = null,
) {
    /** More than one disc, which is when the numbering starts over and needs "Disc N" headers. */
    val isMultiDisc: Boolean
        get() = tracks.mapNotNull { it.parentIndexNumber }.distinct().size > 1

    /** Server order, grouped by disc; a missing disc number falls under disc 1. */
    val tracksByDisc: List<Pair<Int, List<JellyfinItem>>>
        get() =
            tracks
                .groupBy { it.parentIndexNumber ?: 1 }
                .toSortedMap()
                .map { (disc, discTracks) -> disc to discTracks }

    /**
     * Derived from the **tracks**: an album is a folder, and `DownloadEnqueuer` expands a tap on one
     * into a download per track, so there is never a download row keyed on the album itself.
     */
    val albumDownloadState: DownloadState
        get() =
            when {
                tracks.isEmpty() -> DownloadState.NotDownloaded
                tracks.all { it.downloadState is DownloadState.Downloaded } -> DownloadState.Downloaded
                tracks.any { it.downloadState.isActive } -> DownloadState.Downloading(downloadedFraction)
                tracks.any { it.downloadState is DownloadState.Failed } -> DownloadState.Failed
                else -> DownloadState.NotDownloaded
            }

    val canDownload: Boolean
        get() = tracks.any { it.downloadState.isDownloadable }

    /**
     * Over the whole album: a finished track counts 1, so a ten-track album three tracks in reads
     * ~30 % rather than the progress of whichever file is moving.
     */
    private val downloadedFraction: Float
        get() =
            tracks
                .sumOf { track ->
                    when (val state = track.downloadState) {
                        is DownloadState.Downloaded -> 1.0
                        is DownloadState.Downloading -> state.progress.toDouble()
                        else -> 0.0
                    }
                }.toFloat() / tracks.size
}

internal fun AlbumDetailUiState.withDownloadStates(states: Map<String, DownloadState>): AlbumDetailUiState =
    copy(tracks = tracks.map { it.withDownloadState(states) })
