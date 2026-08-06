package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.userdata.UserDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for [PlaylistDetailScreen] — a **view-only** track list (playlist editing is out of
 * M13's scope; docs/notes/music-m13-plan.md).
 *
 * Offline, [JellyfinRepository.getPlaylistItems] always answers empty — Room has no
 * playlist-membership relation, and M13 Phase 5 deliberately did not add one (the deferred item
 * "offline playlist membership"; DECISIONS.md, 2026-08-05) — so an offline visit to this screen
 * simply shows the empty state; nothing here has to special-case connectivity to be honest about
 * that. Downloading *from* a playlist works regardless: the tracks land under their own albums.
 */
@HiltViewModel
class PlaylistDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val playlistId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_PLAYLIST_ID)) {
                "PlaylistDetail route is missing its '$ARG_PLAYLIST_ID' argument"
            }

        private val _uiState = MutableStateFlow(PlaylistDetailUiState())
        val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

        private var downloadStates: Map<String, DownloadState> = emptyMap()

        init {
            load()
            observeUserDataChanges()
            observeDownloadStates()
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

        /** The app-wide badge feed — a playlist's tracks carry the same badge as anywhere else. */
        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads
                    .observeStates()
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the playlist badges")
                        emit(emptyMap())
                    }.collect { states ->
                        downloadStates = states
                        _uiState.update { it.withDownloadStates(states) }
                    }
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
                            it
                                .copy(
                                    isLoading = false,
                                    playlist = playlistResult.value,
                                    tracks = tracksResult.getOrNull().orEmpty(),
                                    errorMessage = null,
                                ).withDownloadStates(downloadStates)
                        }
                }
            }
        }

        companion object {
            /** Key the navigation library stores `Routes.PlaylistDetail.playlistId` under. */
            const val ARG_PLAYLIST_ID = "playlistId"
        }
    }
