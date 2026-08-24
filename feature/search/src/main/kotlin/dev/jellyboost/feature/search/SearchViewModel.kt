package dev.jellyboost.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.observeBadgeStates
import dev.jellyboost.data.reloadOnChange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the search screen.
 *
 * Typing feeds a [MutableStateFlow] that is debounced by [DEBOUNCE_MILLIS] before it reaches the
 * server: a user typing "westworld" must cost one request, not nine. `collectLatest` additionally
 * cancels a search that is still in flight when the term
 * changes again, so a slow response can never overwrite the results of a newer term.
 *
 * Clearing the field bypasses the debounce — an empty screen should appear the moment the text
 * goes away, not half a second later.
 *
 * Search is deliberately unpaged: one capped request split into type sections, which is what
 * jellyfin-web's search does.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SearchUiState())

        /** The single source of truth for [SearchScreen]. */
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        private val typedQuery = MutableStateFlow("")

        /** Last download-state map seen, re-applied whenever a search replaces the results. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

        init {
            viewModelScope.launch {
                typedQuery
                    .map { it.trim() }
                    // A cleared field needs no round trip and no waiting.
                    .debounce { term -> if (term.isEmpty()) 0L else DEBOUNCE_MILLIS }
                    .distinctUntilChanged()
                    .collectLatest { term -> search(term) }
            }
            // One subscription for the whole screen, error-guarded — see `observeBadgeStates`.
            viewModelScope.launch {
                downloads.observeBadgeStates(screen = "search").collect { states ->
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
            observeConnectivityChanges()
        }

        /**
         * Re-runs the current term whenever the connection changes: a search made offline only
         * looked at downloaded items and one made online at everything, and the field keeps its text
         * either way — so the results have to follow the connection, in both directions.
         *
         * An empty field has nothing to re-run — it is not a search waiting for a better connection.
         */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(
                viewModelScope,
                onlyIf = { _uiState.value.query.isNotBlank() },
            ) { retry() }
        }

        /** Called on every keystroke. */
        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
            typedQuery.value = query
        }

        /** Empties the field and the results. */
        fun clearQuery() {
            onQueryChange("")
        }

        /** Re-runs the current search after a failure. */
        fun retry() {
            viewModelScope.launch { search(_uiState.value.query.trim()) }
        }

        private suspend fun search(term: String) {
            if (term.isEmpty()) {
                _uiState.update { it.cleared() }
                return
            }

            _uiState.update { it.copy(isSearching = true, error = null) }

            val result =
                repository.getItems(
                    ItemQuery(
                        searchTerm = term,
                        itemTypes = SEARCH_ITEM_TYPES,
                        recursive = true,
                        limit = SEARCH_LIMIT,
                    ),
                )

            _uiState.update { state -> state.withSearchResult(term, result).withDownloadStates(downloadStates) }
        }

        companion object {
            const val DEBOUNCE_MILLIS = 500L

            const val SEARCH_LIMIT = 50

            /**
             * Every type this client can open from a search result: movies, shows and episodes,
             * plus four music kinds.
             */
            val SEARCH_ITEM_TYPES =
                listOf(
                    ItemType.MOVIE,
                    ItemType.SERIES,
                    ItemType.EPISODE,
                    ItemType.MUSIC_ARTIST,
                    ItemType.MUSIC_ALBUM,
                    ItemType.AUDIO,
                    ItemType.PLAYLIST,
                )
        }
    }
