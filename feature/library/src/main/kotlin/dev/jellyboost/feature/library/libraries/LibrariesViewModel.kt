package dev.jellyboost.feature.library.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.reloadOnChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibrariesViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val connectivityRefresher: ConnectivityRefresher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LibrariesUiState())

        val uiState: StateFlow<LibrariesUiState> = _uiState.asStateFlow()

        init {
            load()
            observeConnectivityChanges()
        }

        /** The offline list only contains libraries this device has already seen. */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(viewModelScope) { refresh() }
        }

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
