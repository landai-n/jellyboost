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

    /**
     * What the header's Download control shows — the album's state, derived from its **tracks**
     * (M13 Phase 5).
     *
     * There is never a download row keyed on the album itself: an album is a folder, and
     * `DownloadEnqueuer` expands a tap on one into one download per track (the same rule that keeps
     * a season from ever being a row). So the only honest answer about "is this album on the
     * device" is the one its tracks give, and this reads it the way `:feature:detail` reads a
     * season's episodes.
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

    /** `true` while tapping Download would actually queue something. */
    val canDownload: Boolean
        get() = tracks.any { it.downloadState.isDownloadable }

    /**
     * How much of the album is on the device, `0f..1f`: a finished track counts as one and a
     * transferring one as its own fraction, so a ten-track album three tracks in reads ~30 % rather
     * than the progress of whichever file happens to be moving.
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

/** Stamps the app-wide download-state map onto every track (M7 badge pattern). */
internal fun AlbumDetailUiState.withDownloadStates(states: Map<String, DownloadState>): AlbumDetailUiState =
    copy(tracks = tracks.map { it.withDownloadState(states) })
