package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.observeBadgeStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * @param state the ViewModel's own holder, **shared not copied** — everything here writes through it
 *   so the screen still sees exactly one [ItemDetailUiState].
 * @param scope the owner's `viewModelScope`: every collector here dies with the ViewModel.
 */
internal class DetailDownloadsDelegate(
    private val downloads: DownloadRepository,
    private val state: MutableStateFlow<ItemDetailUiState>,
    private val itemId: String,
    private val scope: CoroutineScope,
) {
    /** Last map seen, held so a reload can re-apply badges to the items it just replaced. */
    var states: Map<String, DownloadState> = emptyMap()
        private set

    /** Called once, from the ViewModel's `init`. */
    fun start() {
        observeDownloadState()
        observeBytesOnDisk()
    }

    /**
     * Cancel stays immediate because nothing finished is lost; only *Downloaded* asks first.
     *
     * *Failed* on a **container** is an enqueue, not a resume: the container has no row of its own
     * to put back in the queue, and enqueueing is what retries the episodes that failed.
     */
    fun onDownloadClick() {
        val current = state.value
        val item = current.item ?: return

        when (current.downloadState) {
            is DownloadState.NotDownloaded -> enqueue(item.id)

            is DownloadState.Failed ->
                if (current.isDownloadContainer) {
                    enqueue(item.id)
                } else {
                    scope.launch {
                        state.report(
                            downloads.resume(item.id),
                            success = UserMessage.DownloadQueued,
                            failure = UserMessage.DownloadFailed,
                        )
                    }
                }

            is DownloadState.Downloaded -> state.update { it.copy(showDeleteConfirmation = true) }

            else -> cancelDownloads()
        }
    }

    /**
     * The `containsKey` filter is load-bearing: episodes with no row are skipped rather than deleted
     * as no-ops, so cancelling a season three episodes in does not run twenty WorkManager cascades.
     */
    fun confirmDeleteDownload() {
        state.update { it.copy(showDeleteConfirmation = false) }
        removeDownloads(
            targets = state.value.downloadTargets.filter { states.containsKey(it) },
            keptCount = 0,
        )
    }

    fun dismissDeleteConfirmation() {
        state.update { it.copy(showDeleteConfirmation = false) }
    }

    private fun observeDownloadState() {
        scope.launch {
            downloads.observeBadgeStates(screen = "detail").collect { badges ->
                // Must be held: `observeStates()` is distinct-until-changed and would not re-emit
                // just because this screen refetched and replaced every item.
                states = badges
                state.update { it.withDownloadStates(badges) }
            }
        }
    }

    /**
     * A container has no row of its own to sum, so its bytes come back `null` and the header falls
     * back to the server size — deliberate, and why this is a separate projection.
     */
    private fun observeBytesOnDisk() {
        scope.launch {
            downloads
                .observeBytesOnDisk(itemId)
                .catch { error ->
                    Timber.w(error, "The bytes-on-disk flow failed; falling back to the server size")
                    emit(null)
                }.collect { bytes ->
                    state.update { it.copy(downloadedBytes = bytes) }
                }
        }
    }

    private fun enqueue(id: String) {
        scope.launch {
            state.report(
                downloads.enqueue(id),
                success = UserMessage.DownloadQueued,
                failure = UserMessage.DownloadFailed,
            )
        }
    }

    /**
     * Must **not** run the same delete as Remove: a user stopping a season three episodes in would
     * lose those three. A partly-kept season aggregates back to *NotDownloaded*, so the button then
     * offers *Download* for the rest.
     */
    private fun cancelDownloads() {
        val targets = state.value.downloadTargets.mapNotNull { id -> states[id]?.let { id to it } }
        val (finished, inFlight) = targets.partition { (_, downloadState) -> downloadState is DownloadState.Downloaded }

        removeDownloads(targets = inFlight.map { (id, _) -> id }, keptCount = finished.size)
    }

    /**
     * **One batch call, never a loop.** Every single delete stops and restarts the download worker,
     * and each restart hands the queue the next doomed episode — asking the server for a transcode
     * the next iteration cancels. `deleteAll` also prunes metadata once instead of once per row.
     *
     * @param keptCount finished downloads deliberately left alone; the snackbar reports it.
     */
    private fun removeDownloads(
        targets: List<String>,
        keptCount: Int,
    ) {
        if (state.value.item == null || targets.isEmpty()) return

        scope.launch {
            val failed = downloads.deleteAll(targets) is AppResult.Failure
            val message =
                when {
                    failed -> UserMessage.DownloadDeleteFailed
                    keptCount > 0 -> UserMessage.DownloadCancelledKeepingFinished(keptCount)
                    else -> UserMessage.DownloadDeleted
                }
            state.update { it.copy(userMessage = message) }
        }
    }
}
