package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.userdata.UserDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for [PlaylistDetailScreen] — a **view-only** track list (playlist editing is out of
 * M13's scope; docs/notes/music-m13-plan.md).
 *
 * Offline, [JellyfinRepository.getPlaylistItems] always answers empty — Room has no
 * playlist-membership relation before M13 Phase 5 — so an offline visit to this screen simply shows
 * the empty state; nothing here has to special-case connectivity to be honest about that.
 */
@HiltViewModel
class PlaylistDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val playlistId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_PLAYLIST_ID)) {
                "PlaylistDetail route is missing its '$ARG_PLAYLIST_ID' argument"
            }

        private val _uiState = MutableStateFlow(PlaylistDetailUiState())
        val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

        init {
            load()
            observeUserDataChanges()
            observeConnectivityChanges()
        }

        fun refresh() {
            load()
        }

        fun toggleFavorite(item: JellyfinItem) {
            viewModelScope.launch {
                userDataRepository.setFavorite(item.id, !item.userData.isFavorite)
            }
        }

        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect { refresh() }
            }
        }

        private fun observeUserDataChanges() {
            viewModelScope.launch {
                userDataRepository.changes.collect { change ->
                    _uiState.update { state ->
                        state.copy(
                            playlist = state.playlist?.withUserDataIfMatching(change.itemId, change.userData),
                            tracks = state.tracks.map { it.withUserDataIfMatching(change.itemId, change.userData) },
                        )
                    }
                }
            }
        }

        private fun load() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                val (playlistResult, tracksResult) =
                    coroutineScope {
                        val playlist = async { repository.getItem(playlistId) }
                        val tracks = async { repository.getPlaylistItems(playlistId) }
                        playlist.await() to tracks.await()
                    }

                when (playlistResult) {
                    is AppResult.Failure ->
                        _uiState.update { it.copy(isLoading = false, errorMessage = playlistResult.error) }

                    is AppResult.Success ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlist = playlistResult.value,
                                tracks = tracksResult.getOrNull().orEmpty(),
                                errorMessage = null,
                            )
                        }
                }
            }
        }

        companion object {
            /** Key the navigation library stores `Routes.PlaylistDetail.playlistId` under. */
            const val ARG_PLAYLIST_ID = "playlistId"
        }
    }
