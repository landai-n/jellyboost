package dev.jellyboost.feature.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for [MusicLibraryScreen] — Albums, Artists and Playlists, each its own paged grid
 * over [JellyfinRepository.getItemsPaged].
 *
 * **Artists** ask `getItemsPaged` for [ItemType.MUSIC_ARTIST] scoped to this library, the same
 * shape the Albums tab uses. Jellyfin normally serves artists through a dedicated `/Artists`
 * endpoint (`ArtistsApi`), but a plain `getItems(types=[MUSIC_ARTIST], recursive=true,
 * parentId=libraryId)` also answers correctly on the dev server (10.11) — verified against the
 * SDK's request shape; a live-server check is still owed.
 * Reusing `getItemsPaged` keeps one query mechanism for all three tabs rather than a fourth
 * repository member for a single screen.
 *
 * **Playlists** deliberately carry **no** `parentId`. On a Jellyfin server a library's playlists do
 * not live inside that library's own folder — the server keeps one library-wide (sometimes
 * per-user) "Playlists" root instead — so scoping the query to `libraryId` the way Albums/Artists
 * do would ask the wrong folder and come back empty. `getItems(types=[PLAYLIST], recursive=true)`
 * with no `parentId` searches the whole tree instead, which is the defensible query without a live
 * server to confirm against; device verification is still owed.
 */
@HiltViewModel
class MusicLibraryViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val downloads: DownloadRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val libraryId: String = savedStateHandle.get<String>(KEY_LIBRARY_ID).orEmpty()

        private val _uiState =
            MutableStateFlow(
                MusicLibraryUiState(libraryName = savedStateHandle.get<String>(KEY_LIBRARY_NAME).orEmpty()),
            )
        val uiState: StateFlow<MusicLibraryUiState> = _uiState.asStateFlow()

        /**
         * The app-wide download-state map, mirrored here — one subscription serving all three grids
         * (precedent: `LibraryViewModel.downloadStates`).
         */
        private val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

        val albums: Flow<PagingData<JellyfinItem>> =
            withDownloadStates(
                repository.getItemsPaged(
                    ItemQuery(
                        parentId = libraryId,
                        itemTypes = ALBUM_TYPES,
                        recursive = true,
                        sortBy = SortBy.SORT_NAME,
                    ),
                ),
            )

        val artists: Flow<PagingData<JellyfinItem>> =
            withDownloadStates(
                repository.getItemsPaged(
                    ItemQuery(
                        parentId = libraryId,
                        itemTypes = ARTIST_TYPES,
                        recursive = true,
                        sortBy = SortBy.SORT_NAME,
                    ),
                ),
            )

        val playlists: Flow<PagingData<JellyfinItem>> =
            withDownloadStates(
                // No `parentId` — see the class KDoc.
                repository.getItemsPaged(
                    ItemQuery(itemTypes = PLAYLIST_TYPES, recursive = true, sortBy = SortBy.SORT_NAME),
                ),
            )

        init {
            observeDownloadStates()
        }

        private fun withDownloadStates(paged: Flow<PagingData<JellyfinItem>>): Flow<PagingData<JellyfinItem>> =
            paged
                .cachedIn(viewModelScope)
                .combine(downloadStates) { paging, states ->
                    paging.map { item ->
                        val next = states[item.id] ?: DownloadState.NotDownloaded
                        if (next == item.downloadState) item else item.copy(downloadState = next)
                    }
                }

        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads
                    .observeStates()
                    // Degrade to no badges rather than freezing them — see
                    // `HomeViewModel.observeDownloadStates`.
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the music library badges")
                        emit(emptyMap())
                    }.collect { states -> downloadStates.value = states }
            }
        }

        /** Switches which grid is on screen. */
        fun selectTab(tab: MusicLibraryTab) {
            _uiState.update { it.copy(selectedTab = tab) }
        }

        private companion object {
            /** `Routes.MusicLibrary` property names — Navigation stores type-safe args under these. */
            const val KEY_LIBRARY_ID = "libraryId"
            const val KEY_LIBRARY_NAME = "libraryName"

            val ALBUM_TYPES = listOf(ItemType.MUSIC_ALBUM)
            val ARTIST_TYPES = listOf(ItemType.MUSIC_ARTIST)
            val PLAYLIST_TYPES = listOf(ItemType.PLAYLIST)
        }
    }
