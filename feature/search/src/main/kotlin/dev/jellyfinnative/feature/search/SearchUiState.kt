package dev.jellyfinnative.feature.search

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.model.JellyfinItem

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
    /** `true` when the search ran and matched nothing. */
    val hasNoResults: Boolean
        get() = movies.isEmpty() && series.isEmpty() && episodes.isEmpty()
}
