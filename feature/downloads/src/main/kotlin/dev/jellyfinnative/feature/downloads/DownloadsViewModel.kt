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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
                    .collect { (items, storage, wifiOnly) ->
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
