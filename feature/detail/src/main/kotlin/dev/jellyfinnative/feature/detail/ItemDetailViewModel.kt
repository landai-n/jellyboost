package dev.jellyfinnative.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.ReconnectRefresher
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the movie / series / season detail screen.
 *
 * Loads the item in full first — the detail path deliberately re-fetches instead of reusing the
 * lean item a list handed it (docs/PLAN.md, "Screens" → ItemDetail) — then fans out to whatever
 * related rows that item's type calls for, concurrently.
 *
 * Failure policy matches the home screen: only the item itself failing produces an error state; a
 * related row that fails is simply absent.
 *
 * Watched and favourite toggles go through [UserDataRepository], which writes locally and
 * publishes on the user-data event bus this ViewModel collects — so the button flips from the
 * local write, not from a server round-trip.
 */
@HiltViewModel
class ItemDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val downloads: DownloadRepository,
        private val reconnectRefresher: ReconnectRefresher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        /**
         * Navigation stores the arguments of a type-safe route under its property names, so this
         * key is `Routes.ItemDetail::itemId`.
         */
        private val itemId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
                "ItemDetail route is missing its '$ARG_ITEM_ID' argument"
            }

        private val _uiState = MutableStateFlow(ItemDetailUiState())

        /** Last download-state map seen, re-applied whenever the loaded items are replaced. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

        /** The single source of truth for [ItemDetailScreen]. */
        val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            observeDownloadState()
            observeReconnects()
        }

        /**
         * Re-fetches this item once the server is reachable again (M9).
         *
         * Offline, `getItem` answers from the cache — and for anything that is not downloaded, with
         * a placeholder carrying `available = false`. That page has to become the real one on its
         * own; a user who reconnects while looking at it should not have to back out and return.
         */
        private fun observeReconnects() {
            viewModelScope.launch {
                reconnectRefresher.reconnected.collect { refresh() }
            }
        }

        /**
         * Keeps the Download button — and the badge on every season, episode and related card —
         * in step with the pipeline.
         *
         * One subscription to the whole map rather than one per visible item: an episode list can
         * hold forty rows, and forty Room Flows re-emitting on every throttled progress write is
         * exactly the cost `observeStates()` exists to avoid.
         */
        private fun observeDownloadState() {
            viewModelScope.launch {
                downloads.observeStates().collect { states ->
                    // Held so that a later load — which replaces every item in the state — can
                    // re-apply them. `observeStates()` is distinct-until-changed and would not
                    // re-emit just because this screen refetched.
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
        }

        /** Re-fetches the item and its rows; backs pull-to-refresh and the error state's retry. */
        fun refresh() {
            load(isRefresh = true)
        }

        /** Toggles the watched flag, optimistically via the user-data event bus. */
        fun toggleWatched() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                report(userDataRepository.setPlayed(item.id, !item.userData.played))
            }
        }

        /** Toggles the favourite flag, optimistically via the user-data event bus. */
        fun toggleFavorite() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                report(userDataRepository.setFavorite(item.id, !item.userData.isFavorite))
            }
        }

        /**
         * The Download button. One button, two meanings, decided by the current state:
         *
         * - not on the device → enqueue it (M7's pipeline takes it from there);
         * - failed → put it back in the queue, which resumes from the bytes already on disk rather
         *   than starting the transfer over;
         * - anything else (queued, downloading, paused, downloaded) → remove it.
         *
         * A separate "delete" affordance next to it would be dead most of the time, and "tap again
         * to undo" is what the same button already means everywhere else on this screen (watched,
         * favourite).
         */
        fun onDownloadClick() {
            val item = _uiState.value.item ?: return

            viewModelScope.launch {
                when (_uiState.value.downloadState) {
                    is DownloadState.NotDownloaded ->
                        reportDownload(
                            downloads.enqueue(item.id),
                            success = UserMessage.DownloadQueued,
                            failure = UserMessage.DownloadFailed,
                        )

                    is DownloadState.Failed ->
                        reportDownload(
                            downloads.resume(item.id),
                            success = UserMessage.DownloadQueued,
                            failure = UserMessage.DownloadFailed,
                        )

                    else ->
                        reportDownload(
                            downloads.delete(item.id),
                            success = UserMessage.DownloadDeleted,
                            failure = UserMessage.DownloadDeleteFailed,
                        )
                }
            }
        }

        private fun reportDownload(
            result: AppResult<*>,
            success: UserMessage,
            failure: UserMessage,
        ) {
            _uiState.update {
                it.copy(userMessage = if (result is AppResult.Success) success else failure)
            }
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        private fun report(result: AppResult<*>) {
            if (result is AppResult.Failure) {
                _uiState.update { it.copy(userMessage = UserMessage.UserDataWriteFailed) }
            }
        }

        private fun observeUserDataChanges() {
            viewModelScope.launch {
                userDataRepository.changes.collect { change ->
                    _uiState.update { it.withUserData(change.itemId, change.userData) }
                }
            }
        }

        private fun load(isRefresh: Boolean) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null)
                }

                when (val result = repository.getItem(itemId)) {
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = result.error.toMessage(),
                            )
                        }

                    is AppResult.Success -> emitDetail(result.value)
                }
            }
        }

        private suspend fun emitDetail(item: JellyfinItem) {
            val related = fetchRelated(item)
            _uiState.update {
                it
                    .copy(
                        isLoading = false,
                        isRefreshing = false,
                        item = item,
                        seasons = related.seasons,
                        episodes = related.episodes,
                        nextUp = related.nextUp,
                        similar = related.similar,
                        errorMessage = null,
                    ).withDownloadStates(downloadStates)
            }
        }

        /**
         * Fetches the rows [item]'s type calls for, all at once: a series page is bound by its
         * slowest request rather than by the sum of three.
         */
        private suspend fun fetchRelated(item: JellyfinItem): Related =
            coroutineScope {
                val isSeries = item.type == ItemType.SERIES
                val seasonId = item.id.takeIf { item.type == ItemType.SEASON }
                val seriesId = item.seriesId

                val seasons =
                    if (isSeries) async { repository.getSeasons(item.id).getOrNull().orEmpty() } else null
                val nextUp =
                    if (isSeries) async { repository.getNextUpForSeries(item.id).getOrNull() } else null
                val episodes =
                    if (seasonId != null && seriesId != null) {
                        async { repository.getEpisodes(seriesId, seasonId).getOrNull().orEmpty() }
                    } else {
                        null
                    }
                val similar =
                    if (item.type in SIMILAR_TYPES) {
                        async { repository.getSimilarItems(item.id).getOrNull().orEmpty() }
                    } else {
                        null
                    }

                Related(
                    seasons = seasons?.await().orEmpty(),
                    episodes = episodes?.await().orEmpty(),
                    nextUp = nextUp?.await(),
                    similar = similar?.await().orEmpty(),
                )
            }

        private data class Related(
            val seasons: List<JellyfinItem>,
            val episodes: List<JellyfinItem>,
            val nextUp: JellyfinItem?,
            val similar: List<JellyfinItem>,
        )

        companion object {
            /** Key the navigation library stores `Routes.ItemDetail.itemId` under. */
            const val ARG_ITEM_ID = "itemId"

            /**
             * Types the server has meaningful recommendations for. A season is browsed through its
             * series, so "more like this season" would be noise.
             */
            private val SIMILAR_TYPES = setOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)
        }
    }

/** Turns the domain failure taxonomy into copy a user can act on. */
internal fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> "Can't reach your server. Check your connection and try again."
        is AppError.ServerResolution -> "Can't reach your server. Check your connection and try again."
        is AppError.Unauthorized -> "Your session expired. Sign in again to continue."
        is AppError.NotFound -> "That item is no longer on the server."
        is AppError.Server -> "The server returned an error${statusCode?.let { " ($it)" }.orEmpty()}."
        is AppError.Storage -> "Couldn't read local data."
        is AppError.Unknown -> "Something went wrong loading this item."
    }
