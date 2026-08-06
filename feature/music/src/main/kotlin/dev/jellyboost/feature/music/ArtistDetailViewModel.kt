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
 * State holder for [ArtistDetailScreen]: the artist's own metadata, their albums (newest first)
 * and their top tracks, fetched concurrently (M13 Phase 2, docs/notes/music-m13-plan.md).
 */
@HiltViewModel
class ArtistDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val artistId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ARTIST_ID)) {
                "ArtistDetail route is missing its '$ARG_ARTIST_ID' argument"
            }

        private val _uiState = MutableStateFlow(ArtistDetailUiState())
        val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

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

        /** Reveals the rest of the top-tracks list. */
        fun expandTopTracks() {
            _uiState.update { it.copy(topTracksExpanded = true) }
        }

        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect { refresh() }
            }
        }

        /** The app-wide badge feed, kept for [load] to stamp onto a freshly-fetched page. */
        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads
                    .observeStates()
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the artist badges")
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
                            artist = state.artist?.withUserDataIfMatching(change.itemId, change.userData),
                            albums = state.albums.map { it.withUserDataIfMatching(change.itemId, change.userData) },
                            topTracks =
                                state.topTracks.map { it.withUserDataIfMatching(change.itemId, change.userData) },
                        )
                    }
                }
            }
        }

        private fun load() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                val (artistResult, albums, topTracks) =
                    coroutineScope {
                        val artist = async { repository.getItem(artistId) }
                        val albumsDeferred = async { repository.getArtistAlbums(artistId) }
                        val topTracksDeferred = async { repository.getArtistTopTracks(artistId) }
                        Triple(artist.await(), albumsDeferred.await(), topTracksDeferred.await())
                    }

                when (artistResult) {
                    is AppResult.Failure ->
                        _uiState.update { it.copy(isLoading = false, errorMessage = artistResult.error) }

                    is AppResult.Success ->
                        _uiState.update {
                            it
                                .copy(
                                    isLoading = false,
                                    artist = artistResult.value,
                                    albums = albums.getOrNull().orEmpty(),
                                    topTracks = topTracks.getOrNull().orEmpty(),
                                    errorMessage = null,
                                ).withDownloadStates(downloadStates)
                        }
                }
            }
        }

        companion object {
            /** Key the navigation library stores `Routes.ArtistDetail.artistId` under. */
            const val ARG_ARTIST_ID = "artistId"
        }
    }
