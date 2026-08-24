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
 * Holds no download state of its own: everything visible is a projection of three Room/DataStore
 * flows, which is what makes the screen correct across a process death mid-transfer with no
 * save/restore code.
 *
 * [projection] and [local] must stay separate flows so the projection can be **stopped**: shared
 * with [SharingStarted.WhileSubscribed], leaving the screen unsubscribes from the download list and
 * the storage walk. A collector launched in `init` would keep both running from the first visit
 * until process death — the tab switch that "leaves" this screen saves rather than pops it.
 *
 * The projection runs on [DefaultDispatcher]: the grouping, sorting and `lowercase()` in [toGroups]
 * must not run on `Main.immediate` at two-to-six emissions a second for a whole transfer.
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

        val uiState: StateFlow<DownloadsUiState> =
            combine(projection(), local, DownloadsProjection::toUiState)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = DownloadsUiState(),
                )

        /**
         * The tracker, ratchet and cache are built **inside** the flow, once per subscription: all
         * three derive from successive emissions, and one carried over from a subscription that
         * ended minutes ago would measure against a stale timestamp.
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
                            downloaded = groupCache.groups(items),
                            queue = queue,
                            speeds = speedTracker.update(items, clock.millis()),
                            // The queue subset, not the whole table: nothing else reads the ratchet.
                            progress = progressRatchet.update(queue),
                            storage = storage,
                            wifiOnly = wifiOnly,
                        )
                    },
                )
            }
                // Load-bearing: `isLoading` starts `true` and only a first emission clears it, so an
                // upstream throw (a corrupt dto blob raising `SQLiteBlobTooBigException`) would
                // otherwise leave a spinner that never stops. A collapsed flow cannot recover, so
                // the state says so permanently.
                .catch { error ->
                    Timber.e(error, "The downloads projection failed; showing the error state")
                    emit(DownloadsProjection(loadFailed = true))
                }.flowOn(defaultDispatcher)

        fun selectTab(tab: DownloadsTab) {
            local.update { it.copy(selectedTab = tab) }
        }

        fun setWifiOnly(enabled: Boolean) {
            viewModelScope.launch { downloads.setWifiOnly(enabled) }
        }

        // The five row actions must keep taking an item **id**, not a `DownloadItem`: a row
        // parameter would force `QueueRowActions` to take one too, and it is unstable and rebuilt on
        // every progress tick.

        fun pause(itemId: String) {
            viewModelScope.launch { report(downloads.pause(itemId), DownloadsMessage.ActionFailed) }
        }

        fun resume(itemId: String) {
            viewModelScope.launch { report(downloads.resume(itemId), DownloadsMessage.ActionFailed) }
        }

        /** Backs *Cancel* in the queue and *Delete* in the downloaded list alike. */
        fun delete(itemId: String) {
            viewModelScope.launch { report(downloads.delete(itemId), DownloadsMessage.DeleteFailed) }
        }

        /**
         * Transcodes are skipped, not paused: the server ignores `Range` on a file it is still
         * producing, so pausing one discards the transfer. Shares [DownloadItem.isPauseTarget] with
         * the per-row button so the two cannot drift apart.
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

        /** Silent on success: the rows themselves change to *Waiting* under the user's finger. */
        fun resumeAll() {
            val targets = uiState.value.resumeAllTargets
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.resumeAll(targets.map { it.itemId }), DownloadsMessage.ActionFailed)
            }
        }

        fun requestCancelAll() {
            if (uiState.value.queue.isEmpty()) return
            local.update { it.copy(showCancelAllConfirmation = true) }
        }

        fun dismissCancelAll() {
            local.update { it.copy(showCancelAllConfirmation = false) }
        }

        /**
         * **Finished downloads are never touched** — not by a filter here, but by construction:
         * `toQueue()` excludes `DOWNLOADED` rows, so no id in this list names a completed file.
         */
        fun confirmCancelAll() {
            val targets = uiState.value.queue
            local.update { it.copy(showCancelAllConfirmation = false) }
            if (targets.isEmpty()) return

            viewModelScope.launch {
                report(downloads.deleteAll(targets.map { it.itemId }), DownloadsMessage.DeleteFailed)
            }
        }

        fun moveUp(itemId: String) {
            move(itemId, offset = -1)
        }

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
            /** Long enough to cover a rotation and a there-and-back navigation without restarting. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
