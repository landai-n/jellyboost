package dev.jellyfinnative.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.downloads.model.DownloadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
 */
@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val downloads: DownloadRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val speedTracker = DownloadSpeedTracker()
        private val progressRatchet = DownloadProgressRatchet()
        private val _uiState = MutableStateFlow(DownloadsUiState())

        /** The single source of truth for [DownloadsScreen]. */
        val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                combine(
                    downloads.observeDownloads(),
                    downloads.observeStorage(),
                    downloads.wifiOnly,
                ) { items, storage, wifiOnly -> Triple(items, storage, wifiOnly) }
                    // Without this the screen's failure mode is a spinner that never stops:
                    // `isLoading` starts `true` and only a first emission clears it, so one throw
                    // upstream — a corrupt dto blob raising `SQLiteBlobTooBigException` is the real
                    // one — leaves the user staring at it forever, with no way to tell a slow query
                    // from a broken one. A collapsed flow cannot recover on its own, so the state
                    // says so and the screen offers the failure instead of the spinner.
                    .catch { error ->
                        Timber.e(error, "The downloads projection failed; showing the error state")
                        _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                    }.collect { (items, storage, wifiOnly) ->
                        val speeds = speedTracker.update(items, clock.millis())
                        val progress = progressRatchet.update(items)
                        _uiState.update { state ->
                            state.copy(
                                downloaded = items.toGroups(),
                                queue = items.toQueue(),
                                speeds = speeds,
                                progress = progress,
                                storage = storage,
                                wifiOnly = wifiOnly,
                                isLoading = false,
                                loadFailed = false,
                            )
                        }
                    }
            }
        }

        /** Switches between the *Downloaded* and *Queue* tabs. */
        fun selectTab(tab: DownloadsTab) {
            _uiState.update { it.copy(selectedTab = tab) }
        }

        /** Restricts downloads to unmetered networks, or lifts the restriction. */
        fun setWifiOnly(enabled: Boolean) {
            viewModelScope.launch { downloads.setWifiOnly(enabled) }
        }

        /** Pauses one item; its partial files stay on disk for the next resume. */
        fun pause(item: DownloadItem) {
            viewModelScope.launch { report(downloads.pause(item.itemId), DownloadsMessage.ActionFailed) }
        }

        /** Puts a paused or failed item back in the queue. */
        fun resume(item: DownloadItem) {
            viewModelScope.launch { report(downloads.resume(item.itemId), DownloadsMessage.ActionFailed) }
        }

        /**
         * Removes an item from the device.
         *
         * The same call backs *Cancel* in the queue and *Delete* in the downloaded list: both mean
         * "get this off my device", and a half-transferred file does not deserve its own path.
         */
        fun delete(item: DownloadItem) {
            viewModelScope.launch { report(downloads.delete(item.itemId), DownloadsMessage.DeleteFailed) }
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
            val state = _uiState.value
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
                if (message != null) _uiState.update { it.copy(userMessage = message) }
            }
        }

        /**
         * Puts every paused or failed row back in the queue.
         *
         * Silent on success: the rows themselves change to *Waiting* under the user's finger, which
         * says it better than a snackbar over them would.
         */
        fun resumeAll() {
            val targets = _uiState.value.resumeAllTargets
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.resumeAll(targets.map { it.itemId }), DownloadsMessage.ActionFailed)
            }
        }

        /** *Cancel all* was tapped — ask first; [confirmCancelAll] is what actually removes anything. */
        fun requestCancelAll() {
            if (_uiState.value.queue.isEmpty()) return
            _uiState.update { it.copy(showCancelAllConfirmation = true) }
        }

        /** The *Cancel all* dialog was dismissed; the queue is untouched. */
        fun dismissCancelAll() {
            _uiState.update { it.copy(showCancelAllConfirmation = false) }
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
            val targets = _uiState.value.queue
            _uiState.update { it.copy(showCancelAllConfirmation = false) }
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.deleteAll(targets.map { it.itemId }), DownloadsMessage.DeleteFailed)
            }
        }

        /** Moves a queued item one place towards the front. */
        fun moveUp(item: DownloadItem) {
            move(item, offset = -1)
        }

        /** Moves a queued item one place towards the back. */
        fun moveDown(item: DownloadItem) {
            move(item, offset = 1)
        }

        private fun move(
            item: DownloadItem,
            offset: Int,
        ) {
            val queue = _uiState.value.queue
            val index = queue.indexOfFirst { it.itemId == item.itemId }
            if (index < 0) return
            val target = (index + offset).coerceIn(0, queue.lastIndex)
            if (target == index) return

            viewModelScope.launch {
                report(downloads.move(item.itemId, target), DownloadsMessage.ActionFailed)
            }
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        private fun report(
            result: AppResult<*>,
            message: DownloadsMessage,
        ) {
            if (result is AppResult.Failure) {
                _uiState.update { it.copy(userMessage = message) }
            }
        }
    }
