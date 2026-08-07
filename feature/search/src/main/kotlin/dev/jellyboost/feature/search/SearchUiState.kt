package dev.jellyboost.feature.search

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.withDownloadStates

/**
 * Everything the search screen draws.
 *
 * Results arrive as one server response and are split here into the three sections the screen
 * renders, in jellyfin-web's order: movies, shows, then episodes (docs/PLAN.md, "Screens" →
 * Search).
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
    val error: AppError? = null,
) {
    /**
     * How many items the current results hold, across all three sections.
     *
     * Derived rather than stored: it is the size of three lists this state already carries, and a
     * separate field would be one more thing every `copy` has to remember to keep true. It exists
     * because the screen has to *say* it — results arriving is the whole outcome of a search, and
     * until the 2026-08-05 accessibility audit (A11Y-09) it happened in complete silence, with the
     * user's focus still in the field and nothing announcing that the page below had filled up.
     */
    val resultCount: Int
        get() = movies.size + series.size + episodes.size

    /** `true` when the search ran and matched nothing. */
    val hasNoResults: Boolean
        get() = resultCount == 0
}

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
    )
