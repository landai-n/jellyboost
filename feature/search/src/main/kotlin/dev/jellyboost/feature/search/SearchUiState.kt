package dev.jellyboost.feature.search

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.withDownloadStates

/**
 * Everything the search screen draws.
 *
 * Results arrive as one server response and are split here into the sections the screen renders,
 * in jellyfin-web's order: movies, shows, episodes, then M13 Phase 2's music kinds — artists,
 * albums, songs, playlists (docs/PLAN.md, "Screens" → Search; docs/notes/music-m13-plan.md).
 */
data class SearchUiState(
    /** The raw text in the field — echoed back so the field stays a controlled component. */
    val query: String = "",
    /** The term the current results belong to; lags [query] by the debounce. */
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    /** `true` once a search has actually run, so "no results" is not shown before the first one. */
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
     * How many items the current results hold, across every section.
     *
     * Derived rather than stored: it is the size of three lists this state already carries, and a
     * separate field would be one more thing every `copy` has to remember to keep true. It exists
     * because the screen has to *say* it — results arriving is the whole outcome of a search, and
     * until the 2026-08-05 accessibility audit (A11Y-09) it happened in complete silence, with the
     * user's focus still in the field and nothing announcing that the page below had filled up.
     */
    val resultCount: Int
        get() =
            movies.size + series.size + episodes.size +
                artists.size + albums.size + songs.size + playlists.size

    /** `true` when the search ran and matched nothing. */
    val hasNoResults: Boolean
        get() = resultCount == 0
}

/** The blank-query reset — every section empty, nothing pending. */
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
 * Applies one server response for [term] onto this state, sectioned by type.
 *
 * A top-level function rather than a `SearchViewModel` method: folding the eight-way section split
 * into `search()` itself pushed that function over detekt's `LongMethod` ceiling, the same reason
 * `ItemDetailViewModel`'s `fetchRelated`/`runSelectionBatch` are top-level functions in that class.
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

/**
 * Stamps the app-wide download-state map onto every result card (M7).
 *
 * The per-list work — and the identity preservation that lets an unaffected section skip
 * recomposition — is `:data:downloads`' shared [withDownloadStates].
 */
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
