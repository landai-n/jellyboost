package dev.jellyboost.feature.search

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem

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

/**
 * Stamps the app-wide download-state map onto every result card (M7).
 *
 * `:core:ui`'s cards render their `DownloadBadge` from `JellyfinItem.downloadState`, so this is all
 * search has to do to show which of its results are already on the device.
 */
internal fun SearchUiState.withDownloadStates(states: Map<String, DownloadState>): SearchUiState =
    copy(
        movies = movies.withDownloadStates(states),
        series = series.withDownloadStates(states),
        episodes = episodes.withDownloadStates(states),
    )

/** Identity is preserved when nothing changed, so Compose skips the untouched sections. */
private fun List<JellyfinItem>.withDownloadStates(states: Map<String, DownloadState>): List<JellyfinItem> {
    val patched =
        map { item ->
            val next = states[item.id] ?: DownloadState.NotDownloaded
            if (next == item.downloadState) item else item.copy(downloadState = next)
        }
    return if (patched.indices.all { patched[it] === this[it] }) this else patched
}
