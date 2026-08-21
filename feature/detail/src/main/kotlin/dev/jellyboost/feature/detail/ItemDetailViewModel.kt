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
 *
 * The **download** half of the page — the button, its confirmation dialog, and the two Room
 * subscriptions behind them — lives in [DetailDownloadsDelegate] (audit CPX-10). It is the one
 * slice of this screen with state of its own that nothing else here reads, and keeping it in this
 * class had begun to cost: four functions were exiled to file scope and a Room collector inlined
 * into `init`, each with a comment naming detekt's function count as the reason. Those are back
 * where they belong, as methods of whichever half owns them.
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
        /**
         * Navigation stores the arguments of a type-safe route under its property names, so this
         * key is `Routes.ItemDetail::itemId`.
         */
        private val itemId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
                "ItemDetail route is missing its '$ARG_ITEM_ID' argument"
            }

        private val _uiState = MutableStateFlow(ItemDetailUiState())

        /**
         * The download button, its confirmation, and the two Room subscriptions behind them.
         *
         * Constructed here rather than injected: three of its four collaborators — the state, the
         * route's item id and the scope — are this instance's, and no graph can supply them. The
         * three entry points below forward to it so the screen keeps one ViewModel to talk to
         * (audit CPX-10; see [DetailDownloadsDelegate] for what the split is for).
         */
        private val downloadsDelegate =
            DetailDownloadsDelegate(
                downloads = downloads,
                state = _uiState,
                itemId = itemId,
                scope = viewModelScope,
            )

        /** The single source of truth for [ItemDetailScreen]. */
        val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

        private val _selection = MutableStateFlow(ItemSelection())

        /**
         * Which **episode rows** are selected (docs/features/batch-selection.md).
         *
         * Scoped to the episode list and to nothing else on the page: the seasons row, *Next up* and
         * *More like this* are navigation surfaces that lead somewhere else, and a season page's one
         * list of comparable things is its episodes. Kept out of [uiState] so a row reading it is
         * not also subscribed to the download progress this page re-emits several times a second.
         */
        val selection: StateFlow<ItemSelection> = _selection.asStateFlow()

        /**
         * The SyncPlay group this device is in, or `null` (M11 Phase 4).
         *
         * Handed through as the session's own flow rather than folded into [uiState]: this page
         * re-emits its state several times a second while a download runs, and a value that changes
         * a handful of times a session has no business riding along with that. It also keeps the
         * whole feature's dependency on SyncPlay to one `:core:common` interface — `:feature:*`
         * modules never see `:player`.
         */
        val activeGroup: StateFlow<SyncPlayGroupHandle?> get() = syncPlaySession.activeGroup

        private val playRequestChannel = Channel<PlayRequest>(Channel.BUFFERED)

        /**
         * Solo play navigations — "open the player on this item, here, now".
         *
         * One-shot events collected by [ItemDetailScreen] (the pattern `:feature:auth`'s
         * `LoginViewModel` uses), because a play tap resolves to *either* a navigation or a request
         * to the group ([onPlay]) and only the ViewModel can tell which. In a group nothing is
         * emitted here at all: the player is opened by the server's answer, through
         * `SyncPlayController.launchRequests`.
         */
        val playRequests: Flow<PlayRequest> = playRequestChannel.receiveAsFlow()

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            downloadsDelegate.start()
            observeConnectivityChanges()
        }

        /**
         * Re-fetches this item whenever the connection changes (M9), in either direction — see
         * [reloadOnChange] for the general argument.
         *
         * Offline, `getItem` answers from the cache — and for anything that is not downloaded, with
         * a placeholder carrying `available = false`. That page has to become the real one when the
         * server returns, and the real one has to become the placeholder when it goes away: a user
         * looking at a detail page across either transition should not have to back out and return
         * to see a Play button that means what it says.
         */
        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(viewModelScope) { refresh() }
        }

        /** Re-fetches the item and its rows; backs pull-to-refresh and the error state's retry. */
        fun refresh() {
            load(isRefresh = true)
        }

        /** Toggles the watched flag, optimistically via the user-data event bus. */
        fun toggleWatched() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                _uiState.report(
                    userDataRepository.setPlayed(item.id, !item.userData.played),
                    UserMessage.UserDataWriteFailed,
                )
            }
        }

        /** Toggles the favourite flag, optimistically via the user-data event bus. */
        fun toggleFavorite() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                _uiState.report(
                    userDataRepository.setFavorite(item.id, !item.userData.isFavorite),
                    UserMessage.UserDataWriteFailed,
                )
            }
        }

        /** The Download button — see [DetailDownloadsDelegate.onDownloadClick] for what it decides. */
        fun onDownloadClick() = downloadsDelegate.onDownloadClick()

        /** The delete-download dialog was confirmed; [DetailDownloadsDelegate] does the removal. */
        fun confirmDeleteDownload() = downloadsDelegate.confirmDeleteDownload()

        /** The delete-download dialog was dismissed without confirming — the download is untouched. */
        fun dismissDeleteConfirmation() = downloadsDelegate.dismissDeleteConfirmation()

        /**
         * Everything the contextual selection bar over the episode list can ask for.
         *
         * One entry point rather than a method per button, so this screen and the library grid hand
         * the shared `SelectionAppBar` the identical lambda (docs/features/batch-selection.md).
         *
         * Unlike the grid, **Select all** is offered and means exactly what it says: an episode list
         * is fetched whole, so "all" is a set the user can see and count. Also unlike the grid, the
         * selection is *not* dropped when the page reloads — [emitDetail] keeps whatever episodes
         * came back — because a reload here is a background refresh (a connectivity change), not
         * something the user asked for.
         *
         * The batch itself is `runSelectionBatch`, shared with the library grid — the skip rule and
         * the container carve-out live there. Selection mode ends before the work starts; the
         * snackbar says when it finished.
         */
        fun onSelection(intent: SelectionIntent) {
            val ids = _selection.value.ids.toList()
            // Read before the `when` clears it: a Run acts on what was selected when it was tapped.
            val action = (intent as? SelectionIntent.Run)?.action

            when (intent) {
                is SelectionIntent.Toggle -> _selection.update { it.toggled(intent.itemId) }
                is SelectionIntent.SelectAll ->
                    _selection.update { it.selecting(_uiState.value.episodes.map(JellyfinItem::id)) }

                // Selection mode ends *before* the work starts, not after: a batch is a series of
                // ordinary single-item calls that can take a while, and a bar left up over a live
                // list invites a second tap on the same selection.
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
         * Play / Resume — **for the group when there is one**, for this device otherwise.
         *
         * The one entry point for every play affordance on this page: the header button (which
         * resolves a series or season to an episode through `ItemDetailUiState.playTarget`) and each
         * episode row's own play button. They share it because the decision it makes is the same one
         * either way, and it is a decision only this class can make — the screen can see neither the
         * group nor the series listing an episode has to be expanded against.
         *
         * **In a group, a play is the group's play** (DECISIONS.md, 2026-07-31, superseding the
         * "the group buttons join Play rather than replace it" rule of M11 Phase 4). It is sent as a
         * `SetNewQueue` carrying the same series-tail expansion and the same resume position the old
         * *Play for group* button used, and this screen deliberately **does not navigate**: nothing
         * plays anywhere until the server broadcasts the resulting queue (key decision 11), and the
         * player is then opened by `SyncPlayController.launchRequests` → `JellyfinNavHost`. Before
         * the fix, this path opened a local player that the group knew nothing about, which sat under
         * a "Waiting for group" overlay for ever (`syncplay-bugreport.md`).
         *
         * Solo — and in a group for anything a group cannot play (`ItemType.isPlayable`, the same
         * narrowing `ItemDetailUiState.groupTarget` makes) — it is the navigation it has always
         * been, emitted on [playRequests].
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
         * [target] and, when it is an episode, everything the series runs after it — the queue a
         * group play is sent as ([onPlay]).
         *
         * This looks like a UI decision and is really an interop one. jellyfin-web's
         * `translateItemsForPlayback` intercepts a group queue holding exactly one episode and — with
         * `EnableNextEpisodeAutoPlay`, the default — replaces it locally with that episode plus every
         * following one across seasons; `QueueCore` then walks the server's playlist by the
         * *expanded* length to copy the playlist-item ids over, reads past the end of our one-entry
         * playlist, throws, and drops the update, so nobody's playback ever starts. Web never trips
         * this on itself because it expands *before* it calls `SetNewQueue`. Sending the same
         * expansion makes the two lengths agree. Movies are untouched: web leaves a single one alone,
         * and a single-item movie queue is verified good.
         *
         * A failed lookup, or an episode the series listing does not contain, falls back to the lone
         * id: a group queue that web may reject beats no request at all, and the caller's snackbar is
         * about the ask going out either way.
         */
        private suspend fun groupPlayQueue(target: JellyfinItem): List<String> {
            val seriesId = target.seriesId
            if (target.type != ItemType.EPISODE || seriesId == null) return listOf(target.id)

            val episodes = repository.getSeriesEpisodes(seriesId).getOrNull().orEmpty()
            val index = episodes.indexOfFirst { it.id == target.id }
            return if (index >= 0) episodes.drop(index).map { it.id } else listOf(target.id)
        }

        /**
         * Sends one *queue* action for whatever this page's Play button resolves to (M11 Phase 4).
         *
         * One entry point rather than a method per action, exactly as [onSelection] is.
         *
         * Nothing local happens, and nothing here waits to see whether it worked in the sense of the
         * group actually moving: the call is a request, the group's queue is the server's, and its
         * result arrives on the SyncPlay websocket (key decision 11). The snackbar therefore reports
         * that the request went out, which is the only thing this screen can honestly claim.
         *
         * A silent no-op when there is no group or nothing playable to send — the buttons are not
         * drawn in either case, so reaching this is a race with the group ending, not a user error.
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
         * Turns one [GroupAction] into the SyncPlay request that carries it.
         *
         * Neither queue action carries a resume position, deliberately: an item added to a queue is
         * not a resume, and the group would be surprised to find it starting in the middle. Playing
         * something *now* does carry one, and that is [onPlay]'s business.
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

        /** Clears the one-shot message once the snackbar has shown it. */
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

        /**
         * Fetches the rows [item]'s type calls for, all at once: a series page is bound by its
         * slowest request rather than by the sum of three.
         */
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
                // Episode detail's "More from this season" row — deliberately a separate fetch from
                // the season page's own `episodes` above, which also drives batch-selection/download.
                val seasonEpisodes =
                    if (isEpisode && seriesId != null && episodeSeasonId != null) {
                        async { repository.getEpisodes(seriesId, episodeSeasonId).getOrNull().orEmpty() }
                    } else {
                        null
                    }
                // The positional successor across the whole series (not `getNextUpForSeries`, which
                // is next-*unwatched* and wrong on a rewatch) — same recipe as `groupPlayQueue`.
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
            // A reload here is a background refresh — a connectivity edge, not a user action — so an
            // open selection is kept rather than dropped. Episodes the server no longer returns fall
            // out of it, because a batch must never act on a row that is not on the screen.
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
            /** Key the navigation library stores `Routes.ItemDetail.itemId` under. */
            const val ARG_ITEM_ID = "itemId"
        }
    }

/** What one [ItemDetailViewModel.fetchRelated] fan-out came back with. */
private data class Related(
    val seasons: List<JellyfinItem>,
    val episodes: List<JellyfinItem>,
    val nextUp: JellyfinItem?,
    val nextEpisode: JellyfinItem?,
    val seasonEpisodes: List<JellyfinItem>,
    val similar: List<JellyfinItem>,
)

/**
 * Types the server has meaningful recommendations for. A season is browsed through its series, so
 * "more like this season" would be noise.
 */
private val SIMILAR_TYPES = setOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)

/**
 * What this screen calls the one branch it does not share: an unclassified failure here happened
 * loading this item. A missing thing is an item, which is already the shared default.
 */
internal val DetailErrorCopy = AppErrorCopy(unknown = R.string.detail_error_unknown)

/** Turns the domain failure taxonomy into copy a user can act on. */
internal fun AppError.toMessage(): UiText = toUiText(DetailErrorCopy)
