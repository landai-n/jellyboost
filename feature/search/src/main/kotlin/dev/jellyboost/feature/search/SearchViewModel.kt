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
 * Typing is debounced by [DEBOUNCE_MILLIS] so "westworld" costs one request, not nine, and
 * `collectLatest` cancels an in-flight search so a slow response cannot overwrite a newer term.
 * Clearing the field bypasses the debounce. Search is deliberately unpaged, as jellyfin-web's is.
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
            viewModelScope.launch {
                downloads.observeBadgeStates(screen = "search").collect { states ->
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
            observeConnectivityChanges()
        }

        /**
         * A search made offline only looked at downloaded items and one made online at everything,
         * and the field keeps its text either way, so the results must follow the connection in both
         * directions. An empty field has nothing to re-run.
         */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(
                viewModelScope,
                onlyIf = { _uiState.value.query.isNotBlank() },
            ) { retry() }
        }

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
            typedQuery.value = query
        }

        fun clearQuery() {
            onQueryChange("")
        }

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
