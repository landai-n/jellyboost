package dev.jellyboost.feature.search

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.withDownloadStates

/** Results arrive as one server response and are split here into jellyfin-web's section order. */
data class SearchUiState(
    val query: String = "",
    /** The term the current results belong to; lags [query] by the debounce. */
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    /** `true` once a search has run, so "no results" is not shown before the first one. */
    val hasSearched: Boolean = false,
    val movies: List<JellyfinItem> = emptyList(),
    val series: List<JellyfinItem> = emptyList(),
    val episodes: List<JellyfinItem> = emptyList(),
    val artists: List<JellyfinItem> = emptyList(),
    val albums: List<JellyfinItem> = emptyList(),
    val songs: List<JellyfinItem> = emptyList(),
    val playlists: List<JellyfinItem> = emptyList(),
    val error: AppError? = null,
) {
    /**
     * Derived rather than stored: one more field would be one more thing every `copy` has to keep
     * true. It exists because the screen has to *say* it — see `ResultCountLine`.
     */
    val resultCount: Int
        get() =
            movies.size + series.size + episodes.size +
                artists.size + albums.size + songs.size + playlists.size

    val hasNoResults: Boolean
        get() = resultCount == 0
}

internal fun SearchUiState.cleared(): SearchUiState =
    copy(
        submittedQuery = "",
        isSearching = false,
        hasSearched = false,
        movies = emptyList(),
        series = emptyList(),
        episodes = emptyList(),
        artists = emptyList(),
        albums = emptyList(),
        songs = emptyList(),
        playlists = emptyList(),
        error = null,
    )

/**
 * A top-level function rather than a method: folding the eight-way split into `search()` pushed
 * that function over detekt's `LongMethod` ceiling.
 */
internal fun SearchUiState.withSearchResult(
    term: String,
    result: AppResult<List<JellyfinItem>>,
): SearchUiState =
    when (result) {
        is AppResult.Success ->
            copy(
                submittedQuery = term,
                isSearching = false,
                hasSearched = true,
                movies = result.value.ofType(ItemType.MOVIE),
                series = result.value.ofType(ItemType.SERIES),
                episodes = result.value.ofType(ItemType.EPISODE),
                artists = result.value.ofType(ItemType.MUSIC_ARTIST),
                albums = result.value.ofType(ItemType.MUSIC_ALBUM),
                songs = result.value.ofType(ItemType.AUDIO),
                playlists = result.value.ofType(ItemType.PLAYLIST),
                error = null,
            )

        is AppResult.Failure ->
            copy(
                submittedQuery = term,
                isSearching = false,
                hasSearched = true,
                movies = emptyList(),
                series = emptyList(),
                episodes = emptyList(),
                artists = emptyList(),
                albums = emptyList(),
                songs = emptyList(),
                playlists = emptyList(),
                error = result.error,
            )
    }

private fun List<JellyfinItem>.ofType(type: ItemType) = filter { it.type == type }

internal fun SearchUiState.withDownloadStates(states: Map<String, DownloadState>): SearchUiState =
    copy(
        movies = movies.withDownloadStates(states),
        series = series.withDownloadStates(states),
        episodes = episodes.withDownloadStates(states),
        artists = artists.withDownloadStates(states),
        albums = albums.withDownloadStates(states),
        songs = songs.withDownloadStates(states),
        playlists = playlists.withDownloadStates(states),
    )
