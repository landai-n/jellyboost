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
import dev.jellyfinnative.core.common.selection.BatchOutcome
import dev.jellyfinnative.core.common.selection.BatchReport
import dev.jellyfinnative.core.common.selection.ItemSelection
import dev.jellyfinnative.core.common.selection.SelectionAction
import dev.jellyfinnative.core.common.selection.SelectionIntent
import dev.jellyfinnative.core.common.selection.runBatch
import dev.jellyfinnative.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyfinnative.core.common.syncplay.SyncPlaySession
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataRepository
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

        /** Last download-state map seen, re-applied whenever the loaded items are replaced. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

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

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            observeDownloadState()
            observeConnectivityChanges()
        }

        /**
         * Re-fetches this item whenever the connection changes (M9), in either direction.
         *
         * Offline, `getItem` answers from the cache — and for anything that is not downloaded, with
         * a placeholder carrying `available = false`. That page has to become the real one when the
         * server returns, and the real one has to become the placeholder when it goes away: a user
         * looking at a detail page across either transition should not have to back out and return
         * to see a Play button that means what it says.
         */
        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect { refresh() }
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
                downloads
                    .observeStates()
                    // Degrade to no badges rather than freezing them — see
                    // `HomeViewModel.observeDownloadStates` (audit STAB-10).
                    .catch { error ->
                        Timber.w(error, "The download-state flow failed; clearing the detail badges")
                        emit(emptyMap())
                    }.collect { states ->
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
                report(userDataRepository.setPlayed(item.id, !item.userData.played), UserMessage.UserDataWriteFailed)
            }
        }

        /** Toggles the favourite flag, optimistically via the user-data event bus. */
        fun toggleFavorite() {
            val item = _uiState.value.item ?: return
            viewModelScope.launch {
                report(
                    userDataRepository.setFavorite(item.id, !item.userData.isFavorite),
                    UserMessage.UserDataWriteFailed,
                )
            }
        }

        /**
         * The Download button. One button, several meanings, decided by the current state:
         *
         * - not on the device → enqueue it (M7's pipeline takes it from there);
         * - failed → put it back in the queue, which resumes from the bytes already on disk rather
         *   than starting the transfer over;
         * - queued, downloading or paused → cancel it. Nothing finished is lost — on a container
         *   the episodes that already completed are explicitly kept — so this stays immediate;
         * - downloaded → ask for confirmation before removing it (docs/POLISH.md — deleting a
         *   finished download straight away, with no way back, was too easy to trigger by accident).
         *   [confirmDeleteDownload] does the actual removal once the user confirms.
         *
         * A separate "delete" affordance next to it would be dead most of the time, and "tap again
         * to undo" is what the same button already means everywhere else on this screen (watched,
         * favourite).
         *
         * On a **season or series** page every one of those meanings is about the episodes under it
         * rather than the item itself (DECISIONS.md, 2026-07-29): enqueue expands the container in
         * `:data:downloads`, and remove/cancel act on each episode row. *Failed* on a container is
         * an enqueue too, not a resume — the container has no row of its own to put back in the
         * queue, and enqueueing is what retries the episodes that failed.
         */
        fun onDownloadClick() {
            val state = _uiState.value
            val item = state.item ?: return

            when (state.downloadState) {
                is DownloadState.NotDownloaded -> enqueue(item.id)

                is DownloadState.Failed ->
                    if (state.isDownloadContainer) {
                        enqueue(item.id)
                    } else {
                        viewModelScope.launch {
                            report(
                                downloads.resume(item.id),
                                success = UserMessage.DownloadQueued,
                                failure = UserMessage.DownloadFailed,
                            )
                        }
                    }

                is DownloadState.Downloaded -> _uiState.update { it.copy(showDeleteConfirmation = true) }

                else -> cancelDownloads()
            }
        }

        /**
         * The delete-download dialog was confirmed — actually remove the item from this device.
         *
         * One row for a movie or an episode; for a season, every episode of it that has a row — the
         * ones that do not are skipped rather than deleted as no-ops, so cancelling a season that is
         * three episodes in does not run twenty pointless cascades through WorkManager.
         */
        fun confirmDeleteDownload() {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            removeDownloads(
                targets = _uiState.value.downloadTargets.filter { downloadStates.containsKey(it) },
                keptCount = 0,
            )
        }

        private fun enqueue(itemId: String) {
            viewModelScope.launch {
                report(
                    downloads.enqueue(itemId),
                    success = UserMessage.DownloadQueued,
                    failure = UserMessage.DownloadFailed,
                )
            }
        }

        /**
         * Cancels a download that is still in flight, **keeping whatever already finished**.
         *
         * On a season, Cancel used to run the same delete as Remove and take the episodes that had
         * completed with it (docs/POLISH.md) — a user stopping a season three episodes in lost those
         * three. Cancel now only touches rows that are queued, transferring, paused or failed. A
         * partly-kept season then aggregates back to *NotDownloaded* (the deliberate
         * some-episodes-missing behaviour), so the button offers *Download* for the rest; removing
         * the kept episodes goes through the Downloads screen's confirmed delete
         * (DECISIONS.md, 2026-07-29).
         */
        private fun cancelDownloads() {
            val targets = _uiState.value.downloadTargets.mapNotNull { id -> downloadStates[id]?.let { id to it } }
            val (finished, inFlight) = targets.partition { (_, state) -> state is DownloadState.Downloaded }

            removeDownloads(targets = inFlight.map { (id, _) -> id }, keptCount = finished.size)
        }

        /**
         * Deletes [targets] and reports how it went. [keptCount] is the number of finished downloads
         * this call deliberately left alone, which is what the snackbar tells the user about.
         */
        private fun removeDownloads(
            targets: List<String>,
            keptCount: Int,
        ) {
            if (_uiState.value.item == null || targets.isEmpty()) return

            viewModelScope.launch {
                val failed = targets.map { downloads.delete(it) }.any { it is AppResult.Failure }
                val message =
                    when {
                        failed -> UserMessage.DownloadDeleteFailed
                        keptCount > 0 -> UserMessage.DownloadCancelledKeepingFinished(keptCount)
                        else -> UserMessage.DownloadDeleted
                    }
                _uiState.update { it.copy(userMessage = message) }
            }
        }

        /** The delete-download dialog was dismissed without confirming — the download is untouched. */
        fun dismissDeleteConfirmation() {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
        }

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
         * The batch itself: *Mark watched / unwatched* is `UserDataRepository`, local-first, so it
         * works with no network; *Download* skips episodes already on the device or already queued
         * ([DownloadState.isDownloadable]) — the same rule `DownloadEnqueuer` applies when it
         * expands a season — and reports the count it skipped. Selection mode ends before the work
         * starts; the snackbar says when it finished.
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
                val outcome = runSelectionBatch(action, ids, downloadStates, userDataRepository, downloads)
                _uiState.update { it.copy(userMessage = UserMessage.BatchFinished(BatchReport(action, outcome))) }
            }
        }

        /**
         * Sends one group action for whatever this page's Play button resolves to (M11 Phase 4).
         *
         * One entry point rather than a method per action, exactly as [onSelection] is — and for the
         * same reason as `runSelectionBatch` below, the dispatch itself is a top-level function: this
         * class is at the project's function-count ceiling (detekt `TooManyFunctions`, threshold 20).
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
                sendGroupAction(action, target, syncPlaySession)
                _uiState.update { it.copy(userMessage = UserMessage.GroupActionSent(action)) }
            }
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        /**
         * Turns one repository result into the snackbar, or into silence.
         *
         * One helper for both kinds of write this screen makes (audit: the class sits on detekt's
         * function-count ceiling, so two near-identical reporters were one too many). A `null`
         * [success] is what the watched / favourite toggles want: they are already visible on the
         * page from the local write, so saying so again would be noise — only a failure is news.
         */
        private fun report(
            result: AppResult<*>,
            failure: UserMessage,
            success: UserMessage? = null,
        ) {
            val message = if (result is AppResult.Success) success else failure
            message?.let { next -> _uiState.update { it.copy(userMessage = next) } }
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

/**
 * Runs one batch action over [ids] and reports how it went.
 *
 * A top-level function rather than a method: `ItemDetailViewModel` is already at the project's
 * function-count ceiling (detekt `TooManyFunctions`, threshold 20), and this dispatch depends on
 * nothing but its arguments — which also makes it readable on its own.
 *
 * - **watched / unwatched** — `UserDataRepository`, which writes Room and publishes on the event bus
 *   *before* it contacts the server, so the whole batch works with no network and the ticks appear
 *   from the local write (docs/PLAN.md, "Data layer").
 * - **download** — episodes already on the device or already queued are not passed to the enqueuer
 *   at all ([DownloadState.isDownloadable]) and are counted as `skipped`; a failed one *is* passed,
 *   because re-enqueueing is how a failure is retried. Offline, each enqueue fails exactly as a
 *   single tap on the same row does today, and the summary reports the failures.
 */
private suspend fun runSelectionBatch(
    action: SelectionAction,
    ids: List<String>,
    downloadStates: Map<String, DownloadState>,
    userDataRepository: UserDataRepository,
    downloads: DownloadRepository,
): BatchOutcome =
    when (action) {
        SelectionAction.MARK_WATCHED -> runBatch(ids) { userDataRepository.setPlayed(it, played = true) }
        SelectionAction.MARK_UNWATCHED -> runBatch(ids) { userDataRepository.setPlayed(it, played = false) }
        SelectionAction.DOWNLOAD -> {
            val targets = ids.filter { (downloadStates[it] ?: DownloadState.NotDownloaded).isDownloadable }
            runBatch(targets, skipped = ids.size - targets.size) { downloads.enqueue(it) }
        }
    }

/**
 * Turns one [GroupAction] into the SyncPlay request that carries it.
 *
 * A top-level function for the same reason `runSelectionBatch` is one: `ItemDetailViewModel` is at
 * detekt's function-count ceiling, and this dispatch depends on nothing but its arguments.
 *
 * *Play for group* deliberately carries the resume position, so watching something together starts
 * where the person who chose it had got to — the same rule the ordinary Play button follows. The two
 * queue actions do not: an item added to the back of a queue is not a resume, and the group would
 * be surprised to find it starting in the middle.
 */
private suspend fun sendGroupAction(
    action: GroupAction,
    target: JellyfinItem,
    session: SyncPlaySession,
) {
    when (action) {
        GroupAction.PLAY_FOR_GROUP ->
            session.playForGroup(target.id, startPositionTicks = playbackStartTicks(target))

        GroupAction.PLAY_NEXT -> session.addToGroupQueue(target.id, next = true)
        GroupAction.ADD_TO_QUEUE -> session.addToGroupQueue(target.id, next = false)
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
