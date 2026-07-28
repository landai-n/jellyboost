package dev.jellyfinnative.feature.library.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.data.JellyfinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the Libraries tab.
 *
 * A thin wrapper over [JellyfinRepository.getUserViews] — the same call the home screen's
 * *My Media* row makes — rendered as its own full grid instead of a horizontal row.
 */
@HiltViewModel
class LibrariesViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LibrariesUiState())

        /** The single source of truth for [LibrariesScreen]. */
        val uiState: StateFlow<LibrariesUiState> = _uiState.asStateFlow()

        init {
            load()
        }

        /** Re-fetches the library list; called by pull-to-refresh and the error state's retry button. */
        fun refresh() {
            load()
        }

        private fun load() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                when (val result = repository.getUserViews()) {
                    is AppResult.Success ->
                        _uiState.update {
                            it.copy(isLoading = false, libraries = result.value, error = null)
                        }

                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(isLoading = false, libraries = emptyList(), error = result.error)
                        }
                }
            }
        }
    }
