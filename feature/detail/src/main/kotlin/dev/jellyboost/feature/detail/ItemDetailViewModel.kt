package dev.jellyboost.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.selection.BatchReport
import dev.jellyboost.core.common.selection.ItemSelection
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.common.selection.runSelectionBatch
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.reloadOnChange
import dev.jellyboost.data.userdata.UserDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The detail path deliberately re-fetches the item in full rather than reusing the lean one a list
 * handed it. Failure policy matches the home screen: only the item itself failing produces an error
 * state; a related row that fails is simply absent.
 */
@HiltViewModel
class ItemDetailViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val userDataRepository: UserDataRepository,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
        private val syncPlaySession: SyncPlaySession,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        /** Navigation stores a type-safe route's arguments under its property names. */
        private val itemId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
                "ItemDetail route is missing its '$ARG_ITEM_ID' argument"
            }

        private val _uiState = MutableStateFlow(ItemDetailUiState())

        /** Not injectable: three of its four collaborators are this instance's own. */
        private val downloadsDelegate =
            DetailDownloadsDelegate(
                downloads = downloads,
                state = _uiState,
                itemId = itemId,
                scope = viewModelScope,
            )

        val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

        private val _selection = MutableStateFlow(ItemSelection())

        /**
         * Episode rows only (docs/features/batch-selection.md). Kept out of [uiState] so a row
         * reading it is not also subscribed to download progress this page re-emits several times a
         * second.
         */
        val selection: StateFlow<ItemSelection> = _selection.asStateFlow()

        /**
         * Kept out of [uiState] for the same reason [selection] is. It also holds the whole feature's
         * SyncPlay dependency to one `:core:common` interface — `:feature:*` never sees `:player`.
         */
        val activeGroup: StateFlow<SyncPlayGroupHandle?> get() = syncPlaySession.activeGroup

        private val playRequestChannel = Channel<PlayRequest>(Channel.BUFFERED)

        /**
         * **Solo** plays only. In a group nothing is emitted here at all: the player is opened by
         * the server's answer, through `SyncPlayController.launchRequests`.
         */
        val playRequests: Flow<PlayRequest> = playRequestChannel.receiveAsFlow()

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            downloadsDelegate.start()
            observeConnectivityChanges()
        }

        /**
         * Both directions matter: offline, `getItem` answers with an `available = false` placeholder,
         * and the page has to swap between that and the real one without the user backing out.
         */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(viewModelScope) { refresh() }
        }

        fun refresh() {
            load(isRefresh = true)
        }

        fun toggleWatched() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                _uiState.report(
                    userDataRepository.setPlayed(item.id, !item.userData.played),
                    UserMessage.UserDataWriteFailed,
                )
            }
        }

        fun toggleFavorite() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                _uiState.report(
                    userDataRepository.setFavorite(item.id, !item.userData.isFavorite),
                    UserMessage.UserDataWriteFailed,
                )
            }
        }

        fun onDownloadClick() = downloadsDelegate.onDownloadClick()

        fun confirmDeleteDownload() = downloadsDelegate.confirmDeleteDownload()

        fun dismissDeleteConfirmation() = downloadsDelegate.dismissDeleteConfirmation()

        /**
         * Unlike the library grid, the selection is *not* dropped on a reload — [emitDetail] keeps
         * whatever episodes came back — because a reload here is a background connectivity refresh,
         * not something the user asked for (docs/features/batch-selection.md).
         */
        fun onSelection(intent: SelectionIntent) {
            val ids = _selection.value.ids.toList()
            // Read before the `when` clears it: a Run acts on what was selected when it was tapped.
            val action = (intent as? SelectionIntent.Run)?.action

            when (intent) {
                is SelectionIntent.Toggle -> _selection.update { it.toggled(intent.itemId) }
                is SelectionIntent.SelectAll ->
                    _selection.update { it.selecting(_uiState.value.episodes.map(JellyfinItem::id)) }

                // Selection mode must end *before* the work starts: a batch takes a while, and a bar
                // left up over a live list invites a second tap on the same selection.
                is SelectionIntent.Clear, is SelectionIntent.Run -> _selection.update { it.cleared() }
            }

            if (action == null || ids.isEmpty()) return
            viewModelScope.launch {
                val outcome =
                    runSelectionBatch(
                        action = action,
                        ids = ids,
                        downloadStates = downloadsDelegate.states,
                        setPlayed = userDataRepository::setPlayed,
                        enqueue = downloads::enqueue,
                    )
                _uiState.update { it.copy(userMessage = UserMessage.BatchFinished(BatchReport(action, outcome))) }
            }
        }

        /**
         * In a group this deliberately **does not navigate**: nothing plays anywhere until the
         * server broadcasts the queue, and opening a local player here would leave it under a
         * "Waiting for group" overlay for ever, since the group knows nothing about it.
         */
        fun onPlay(target: JellyfinItem) {
            val startPositionTicks = playbackStartTicks(target)
            if (syncPlaySession.activeGroup.value == null || !target.type.isPlayable) {
                playRequestChannel.trySend(PlayRequest(target.id, startPositionTicks))
                return
            }

            viewModelScope.launch {
                syncPlaySession.playForGroup(
                    itemIds = groupPlayQueue(target),
                    startPositionTicks = startPositionTicks,
                )
                _uiState.update { it.copy(userMessage = UserMessage.GroupPlayRequested) }
            }
        }

        /**
         * The series-tail expansion is an **interop** requirement, not a UI choice: jellyfin-web's
         * `translateItemsForPlayback` expands a one-episode group queue locally (with
         * `EnableNextEpisodeAutoPlay`, the default), then `QueueCore` walks the server's playlist by
         * that expanded length, reads past the end of a one-entry playlist, throws, and drops the
         * update — so nobody's playback starts. Web never trips this on itself because it expands
         * before calling `SetNewQueue`. Movies are untouched; a single-item movie queue is verified
         * good.
         */
        private suspend fun groupPlayQueue(target: JellyfinItem): List<String> {
            val seriesId = target.seriesId
            if (target.type != ItemType.EPISODE || seriesId == null) return listOf(target.id)

            val episodes = repository.getSeriesEpisodes(seriesId).getOrNull().orEmpty()
            val index = episodes.indexOfFirst { it.id == target.id }
            return if (index >= 0) episodes.drop(index).map { it.id } else listOf(target.id)
        }

        /**
         * The snackbar can only claim the *request* went out: the group's queue is the server's and
         * the result arrives on the SyncPlay websocket (key decision 11). Silently a no-op with no
         * group or nothing playable — reaching that is a race with the group ending, not an error.
         */
        fun onGroupAction(action: GroupAction) {
            val target = _uiState.value.groupTarget ?: return
            if (syncPlaySession.activeGroup.value == null) return

            viewModelScope.launch {
                sendGroupAction(action, target)
                _uiState.update { it.copy(userMessage = UserMessage.GroupActionSent(action)) }
            }
        }

        /**
         * Neither queue action carries a resume position, deliberately: an item added to a queue is
         * not a resume, and the group would be surprised to find it starting in the middle.
         */
        private suspend fun sendGroupAction(
            action: GroupAction,
            target: JellyfinItem,
        ) {
            when (action) {
                GroupAction.PLAY_NEXT -> syncPlaySession.addToGroupQueue(target.id, next = true)
                GroupAction.ADD_TO_QUEUE -> syncPlaySession.addToGroupQueue(target.id, next = false)
            }
        }

        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
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

        /** Concurrent: a series page is bound by its slowest request, not the sum of three. */
        private suspend fun fetchRelated(item: JellyfinItem): Related =
            coroutineScope {
                val isSeries = item.type == ItemType.SERIES
                val isEpisode = item.type == ItemType.EPISODE
                val seasonId = item.id.takeIf { item.type == ItemType.SEASON }
                val seriesId = item.seriesId
                val episodeSeasonId = item.seasonId

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
                // Must stay a separate fetch from `episodes` above, which also drives
                // batch-selection and download.
                val seasonEpisodes =
                    if (isEpisode && seriesId != null && episodeSeasonId != null) {
                        async { repository.getEpisodes(seriesId, episodeSeasonId).getOrNull().orEmpty() }
                    } else {
                        null
                    }
                // Not `getNextUpForSeries`: that is next-*unwatched* and wrong on a rewatch.
                val seriesEpisodesForNext =
                    if (isEpisode && seriesId != null) {
                        async { repository.getSeriesEpisodes(seriesId).getOrNull().orEmpty() }
                    } else {
                        null
                    }
                val similar =
                    if (item.type in SIMILAR_TYPES) {
                        async { repository.getSimilarItems(item.id).getOrNull().orEmpty() }
                    } else {
                        null
                    }

                val allSeriesEpisodes = seriesEpisodesForNext?.await().orEmpty()
                val nextEpisode =
                    allSeriesEpisodes
                        .indexOfFirst { it.id == item.id }
                        .takeIf { it >= 0 }
                        ?.let { allSeriesEpisodes.getOrNull(it + 1) }

                Related(
                    seasons = seasons?.await().orEmpty(),
                    episodes = episodes?.await().orEmpty(),
                    nextUp = nextUp?.await(),
                    nextEpisode = nextEpisode,
                    seasonEpisodes = seasonEpisodes?.await().orEmpty(),
                    similar = similar?.await().orEmpty(),
                )
            }

        private suspend fun emitDetail(item: JellyfinItem) {
            val related = fetchRelated(item)
            // An open selection survives a reload, but episodes the server no longer returns must
            // fall out of it: a batch must never act on a row that is not on the screen.
            _selection.update { it.retaining(related.episodes.map(JellyfinItem::id)) }
            _uiState.update {
                it
                    .copy(
                        isLoading = false,
                        isRefreshing = false,
                        item = item,
                        seasons = related.seasons,
                        episodes = related.episodes,
                        nextUp = related.nextUp,
                        nextEpisode = related.nextEpisode,
                        seasonEpisodes = related.seasonEpisodes,
                        similar = related.similar,
                        errorMessage = null,
                    ).withDownloadStates(downloadsDelegate.states)
            }
        }

        companion object {
            /** Must match `Routes.ItemDetail`'s property name. */
            const val ARG_ITEM_ID = "itemId"
        }
    }

private data class Related(
    val seasons: List<JellyfinItem>,
    val episodes: List<JellyfinItem>,
    val nextUp: JellyfinItem?,
    val nextEpisode: JellyfinItem?,
    val seasonEpisodes: List<JellyfinItem>,
    val similar: List<JellyfinItem>,
)

/** Seasons are excluded: browsed through their series, "more like this season" would be noise. */
private val SIMILAR_TYPES = setOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)

/** Only `unknown` is overridden; the shared default for a missing thing already says "item". */
internal val DetailErrorCopy = AppErrorCopy(unknown = R.string.detail_error_unknown)

internal fun AppError.toMessage(): UiText = toUiText(DetailErrorCopy)
