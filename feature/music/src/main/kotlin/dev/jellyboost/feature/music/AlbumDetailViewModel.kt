package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
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
 * State holder for [AlbumDetailScreen]: the album's own metadata plus its tracks, in disc/track
 * order, fetched concurrently (M13 Phase 2, docs/notes/music-m13-plan.md).
 */
@HiltViewModel
class AlbumDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val albumId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ALBUM_ID)) {
                "AlbumDetail route is missing its '$ARG_ALBUM_ID' argument"
            }

        private val _uiState = MutableStateFlow(AlbumDetailUiState())
        val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

        private var downloadStates: Map<String, DownloadState> = emptyMap()

        init {
            load()
            observeUserDataChanges()
            observeDownloadStates()
            observeConnectivityChanges()
        }

        /** Re-fetches the album and its tracks; backs pull-to-refresh and the error state's retry. */
        fun refresh() {
            load()
        }

        /** Toggles the favourite heart on the album header or on one of its tracks. */
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

        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads
                    .observeStates()
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the track badges")
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
                            album = state.album?.withUserDataIfMatching(change.itemId, change.userData),
                            tracks = state.tracks.map { it.withUserDataIfMatching(change.itemId, change.userData) },
                        )
                    }
                }
            }
        }

        private fun load() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                val (albumResult, tracksResult) =
                    coroutineScope {
                        val album = async { repository.getItem(albumId) }
                        val tracks = async { repository.getAlbumTracks(albumId) }
                        album.await() to tracks.await()
                    }

                when (albumResult) {
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = albumResult.error)
                        }

                    is AppResult.Success -> {
                        val tracks = (tracksResult as? AppResult.Success)?.value.orEmpty()
                        _uiState.update {
                            it
                                .copy(
                                    isLoading = false,
                                    album = albumResult.value,
                                    tracks = tracks,
                                    errorMessage = null,
                                ).withDownloadStates(downloadStates)
                        }
                    }
                }
            }
        }

        companion object {
            /** Key the navigation library stores `Routes.AlbumDetail.albumId` under. */
            const val ARG_ALBUM_ID = "albumId"
        }
    }

/** This item with [userData] applied, if [itemId] names it — the local user-data patch pattern. */
internal fun JellyfinItem.withUserDataIfMatching(
    itemId: String,
    userData: UserData,
): JellyfinItem = if (id == itemId) copy(userData = userData) else this
