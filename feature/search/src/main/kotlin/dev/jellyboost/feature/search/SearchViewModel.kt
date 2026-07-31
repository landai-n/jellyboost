package dev.jellyboost.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the search screen.
 *
 * Typing feeds a [MutableStateFlow] that is debounced by [DEBOUNCE_MILLIS] before it reaches the
 * server (docs/PLAN.md, "Screens" → Search): a user typing "westworld" must cost one request, not
 * nine. `collectLatest` additionally cancels a search that is still in flight when the term
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
            // One subscription for the whole screen — see `HomeViewModel.observeDownloadStates`.
            viewModelScope.launch {
                downloads
                    .observeStates()
                    // Degrade to no badges rather than freezing them — see
                    // `HomeViewModel.observeDownloadStates` (audit STAB-10).
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the search badges")
                        emit(emptyMap())
                    }.collect { states ->
                        downloadStates = states
                        _uiState.update { it.withDownloadStates(states) }
                    }
            }
            observeConnectivityChanges()
        }

        /**
         * Re-runs the current term whenever the connection changes (M9): a search made offline only
         * looked at downloaded items and one made online at everything, and the field keeps its text
         * either way — so the results have to follow the connection, in both directions.
         *
         * An empty field has nothing to re-run — it is not a search waiting for a better connection.
         */
        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect {
                    if (_uiState.value.query.isNotBlank()) retry()
                }
            }
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
                _uiState.update {
                    it.copy(
                        submittedQuery = "",
                        isSearching = false,
                        hasSearched = false,
                        movies = emptyList(),
                        series = emptyList(),
                        episodes = emptyList(),
                        error = null,
                    )
                }
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

            _uiState.update { state ->
                when (result) {
                    is AppResult.Success ->
                        state
                            .copy(
                                submittedQuery = term,
                                isSearching = false,
                                hasSearched = true,
                                movies = result.value.ofType(ItemType.MOVIE),
                                series = result.value.ofType(ItemType.SERIES),
                                episodes = result.value.ofType(ItemType.EPISODE),
                                error = null,
                            ).withDownloadStates(downloadStates)

                    is AppResult.Failure ->
                        state.copy(
                            submittedQuery = term,
                            isSearching = false,
                            hasSearched = true,
                            movies = emptyList(),
                            series = emptyList(),
                            episodes = emptyList(),
                            error = result.error,
                        )
                }
            }
        }

        private fun List<JellyfinItem>.ofType(type: ItemType) = filter { it.type == type }

        companion object {
            /** Debounce from docs/PLAN.md, "Screens" → Search. */
            const val DEBOUNCE_MILLIS = 500L

            /** Result cap from docs/PLAN.md, "Screens" → Search. */
            const val SEARCH_LIMIT = 50

            /** v1 searches movies, shows and episodes — the types this client can play or open. */
            val SEARCH_ITEM_TYPES = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)
        }
    }
