package dev.jellyboost.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.DefaultDispatcher
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.model.DownloadItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject

/**
 * State holder for the Downloads screen.
 *
 * It holds no download state of its own: everything visible is a projection of three Room/DataStore
 * Flows (the downloads, the storage figures, the Wi-Fi-only preference), which is what makes the
 * screen correct across a process death mid-transfer without any save/restore code.
 *
 * The only derived value is the transfer speed, computed by [DownloadSpeedTracker] from successive
 * emissions — see its KDoc for why it is not a column.
 *
 * ### What the state is split into, and why
 * [projection] is everything Room and DataStore answer; [local] is everything only a tap changes
 * (the tab, the *Cancel all* dialog, the snackbar). They are separate flows so that the projection
 * can be **stopped**: it is shared with [SharingStarted.WhileSubscribed], so leaving the screen
 * unsubscribes from it, and with it from the download list and the storage walk it pulls. Before,
 * a single collector launched in `init` kept those queries running from the first visit until
 * process death — with the screen off, and with the tab switch that "left" the screen saving rather
 * than popping it (docs/notes/audit-2026-07.md, PERF-03). [STOP_TIMEOUT_MS] of grace means a
 * rotation or a brief navigation away re-uses the running projection instead of restarting it.
 *
 * The projection itself runs on [DefaultDispatcher]. Grouping, sorting and the per-comparison
 * `lowercase()` in [toGroups] used to run in the collector's context — `Main.immediate` — at the
 * throttle's two-to-six emissions a second for the whole of a transfer.
 */
@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val downloads: DownloadRepository,
        private val clock: Clock,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        /** The single source of truth for [DownloadsScreen]. */
        val uiState: StateFlow<DownloadsUiState> =
            combine(projection(), local, DownloadsProjection::toUiState)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = DownloadsUiState(),
                )

        /**
         * Everything the screen draws that comes from storage.
         *
         * The speed tracker and the ratchet are built *inside* the flow, once per subscription:
         * both derive from successive emissions, and a tracker carried over from a subscription that
         * ended minutes ago would report a speed measured against a stale timestamp.
         */
        private fun projection(): Flow<DownloadsProjection> =
            flow {
                val speedTracker = DownloadSpeedTracker()
                val progressRatchet = DownloadProgressRatchet()
                val groupCache = DownloadGroupCache()
                emitAll(
                    combine(
                        downloads.observeDownloads(),
                        downloads.observeStorage(),
                        downloads.wifiOnly,
                    ) { items, storage, wifiOnly ->
                        val queue = items.toQueue()
                        DownloadsProjection(
                            // Memoised: the finished half does not move during a transfer, but this
                            // flow emits several times a second while one is running (PERF-11).
                            downloaded = groupCache.groups(items),
                            queue = queue,
                            speeds = speedTracker.update(items, clock.millis()),
                            // The queue subset, not the whole table: nothing but a queue row reads
                            // the ratchet's answer (PERF-10).
                            progress = progressRatchet.update(queue),
                            storage = storage,
                            wifiOnly = wifiOnly,
                        )
                    },
                )
            }
                // Without this the screen's failure mode is a spinner that never stops:
                // `isLoading` starts `true` and only a first emission clears it, so one throw
                // upstream — a corrupt dto blob raising `SQLiteBlobTooBigException` is the real
                // one — leaves the user staring at it forever, with no way to tell a slow query
                // from a broken one. A collapsed flow cannot recover on its own, so the state
                // says so and the screen offers the failure instead of the spinner.
                .catch { error ->
                    Timber.e(error, "The downloads projection failed; showing the error state")
                    emit(DownloadsProjection(loadFailed = true))
                }.flowOn(defaultDispatcher)

        /** Switches between the *Downloaded* and *Queue* tabs. */
        fun selectTab(tab: DownloadsTab) {
            local.update { it.copy(selectedTab = tab) }
        }

        /** Restricts downloads to unmetered networks, or lifts the restriction. */
        fun setWifiOnly(enabled: Boolean) {
            viewModelScope.launch { downloads.setWifiOnly(enabled) }
        }

        // The five row actions take an item **id** rather than a `DownloadItem` (audit 2026-08-08,
        // PERF-14). Every one of them only ever used the id, and taking the whole row forced the
        // composables that call them to take one too — `QueueRowActions` was handed a fresh, always-
        // unstable `DownloadItem` on every progress tick where two booleans and an id would do.

        /** Pauses one item; its partial files stay on disk for the next resume. */
        fun pause(itemId: String) {
            viewModelScope.launch { report(downloads.pause(itemId), DownloadsMessage.ActionFailed) }
        }

        /** Puts a paused or failed item back in the queue. */
        fun resume(itemId: String) {
            viewModelScope.launch { report(downloads.resume(itemId), DownloadsMessage.ActionFailed) }
        }

        /**
         * Removes an item from the device.
         *
         * The same call backs *Cancel* in the queue and *Delete* in the downloaded list: both mean
         * "get this off my device", and a half-transferred file does not deserve its own path.
         */
        fun delete(itemId: String) {
            viewModelScope.launch { report(downloads.delete(itemId), DownloadsMessage.DeleteFailed) }
        }

        /**
         * Pauses every queue row that *can* be paused, and says so when some cannot.
         *
         * Transcodes are skipped rather than paused: the server ignores `Range` on a file it is
         * still producing, so pausing one discards the transfer instead of suspending it
         * (`DownloadItem.isPausable`). The per-row button hides *Pause* on exactly those rows, and
         * this shares its predicate ([DownloadItem.isPauseTarget]) so the two cannot drift apart.
         * When anything was skipped the snackbar reports both numbers — a queue that keeps moving
         * after *Pause all* would otherwise read as the button having failed.
         */
        fun pauseAll() {
            val state = uiState.value
            val targets = state.pauseAllTargets
            if (targets.isEmpty()) return
            val stillTranscoding = state.unpausableCount

            viewModelScope.launch {
                val result = downloads.pauseAll(targets.map { it.itemId })
                val message =
                    when {
                        result is AppResult.Failure -> DownloadsMessage.ActionFailed
                        stillTranscoding > 0 ->
                            DownloadsMessage.PausedKeepingTranscodes(
                                pausedCount = targets.size,
                                transcodingCount = stillTranscoding,
                            )

                        else -> null
                    }
                if (message != null) local.update { it.copy(userMessage = message) }
            }
        }

        /**
         * Puts every paused or failed row back in the queue.
         *
         * Silent on success: the rows themselves change to *Waiting* under the user's finger, which
         * says it better than a snackbar over them would.
         */
        fun resumeAll() {
            val targets = uiState.value.resumeAllTargets
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.resumeAll(targets.map { it.itemId }), DownloadsMessage.ActionFailed)
            }
        }

        /** *Cancel all* was tapped — ask first; [confirmCancelAll] is what actually removes anything. */
        fun requestCancelAll() {
            if (uiState.value.queue.isEmpty()) return
            local.update { it.copy(showCancelAllConfirmation = true) }
        }

        /** The *Cancel all* dialog was dismissed; the queue is untouched. */
        fun dismissCancelAll() {
            local.update { it.copy(showCancelAllConfirmation = false) }
        }

        /**
         * Empties the queue: every row it holds is removed in a single batched
         * [DownloadRepository.deleteAll] call.
         *
         * **Finished downloads are never touched** — not by a filter here, but by construction: the
         * queue tab is `toQueue()`, which excludes `DOWNLOADED` rows, so there is no id in this list
         * that names a completed file. This is the season-cancel rule (DECISIONS.md, 2026-07-29,
         * "Cancel on a season keeps the episodes that already finished") applied to the whole queue.
         */
        fun confirmCancelAll() {
            val targets = uiState.value.queue
            local.update { it.copy(showCancelAllConfirmation = false) }
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.deleteAll(targets.map { it.itemId }), DownloadsMessage.DeleteFailed)
            }
        }

        /** Moves a queued item one place towards the front. */
        fun moveUp(itemId: String) {
            move(itemId, offset = -1)
        }

        /** Moves a queued item one place towards the back. */
        fun moveDown(itemId: String) {
            move(itemId, offset = 1)
        }

        private fun move(
            itemId: String,
            offset: Int,
        ) {
            val queue = uiState.value.queue
            val index = queue.indexOfFirst { it.itemId == itemId }
            if (index < 0) return
            val target = (index + offset).coerceIn(0, queue.lastIndex)
            if (target == index) return

            viewModelScope.launch {
                report(downloads.move(itemId, target), DownloadsMessage.ActionFailed)
            }
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        fun consumeMessage() {
            local.update { it.copy(userMessage = null) }
        }

        private fun report(
            result: AppResult<*>,
            message: DownloadsMessage,
        ) {
            if (result is AppResult.Failure) {
                local.update { it.copy(userMessage = message) }
            }
        }

        internal companion object {
            /**
             * How long the projection keeps running after the last subscriber leaves.
             *
             * Long enough to cover a rotation and a there-and-back navigation — restarting the
             * download list and the storage walk for either would cost more than keeping them —
             * and short enough that a screen genuinely left behind stops pulling them.
             */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
