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
 * The download half of the detail screen: the button, the confirmation, and the two Room
 * subscriptions that keep both honest.
 *
 * Separate from [ItemDetailViewModel] because the download button owns a slice of state nothing
 * else on the page touches ([states], the delete-confirmation flag, the bytes-on-disk figure), and
 * the ViewModel's remaining halves — loading the item, user-data toggles, selection, SyncPlay —
 * never read it.
 *
 * A plain class constructed by its owner, not a Hilt binding: [state], [itemId] and [scope] are all
 * per-ViewModel values that no graph can supply. That is the same shape `:player`'s
 * `SyncPlayRejoinPolicy` takes for the same reason — collaborators in the constructor, the owner's
 * scope among them, so nothing has to be handed back in per call.
 *
 * Everything here writes through [state], the ViewModel's own `MutableStateFlow`, so the screen
 * still sees exactly one [ItemDetailUiState] and [ItemDetailViewModel] keeps the whole public API
 * it had before.
 *
 * @param state the ViewModel's state holder, shared rather than copied.
 * @param itemId the detail route's argument — what [ItemDetailUiState.downloadTargets] resolves
 *   against and what the bytes-on-disk projection is keyed on.
 * @param scope the owner's `viewModelScope`: every collector here dies with the ViewModel.
 */
internal class DetailDownloadsDelegate(
    private val downloads: DownloadRepository,
    private val state: MutableStateFlow<ItemDetailUiState>,
    private val itemId: String,
    private val scope: CoroutineScope,
) {
    /**
     * Last download-state map seen, re-applied whenever the loaded items are replaced.
     *
     * Read by the ViewModel too — a batch action over selected episodes skips the ones already on
     * the device, and a reload has to re-apply the badges to the items it just replaced — so it is
     * visible but not writable from there.
     */
    var states: Map<String, DownloadState> = emptyMap()
        private set

    /** Starts both subscriptions; called once, from the ViewModel's `init`. */
    fun start() {
        observeDownloadState()
        observeBytesOnDisk()
    }

    /**
     * The Download button. One button, several meanings, decided by the current state:
     *
     * - not on the device → enqueue it; the download pipeline takes it from there;
     * - failed → put it back in the queue, which resumes from the bytes already on disk rather
     *   than starting the transfer over;
     * - queued, downloading or paused → cancel it. Nothing finished is lost — on a container
     *   the episodes that already completed are explicitly kept — so this stays immediate;
     * - downloaded → ask for confirmation before removing it; deleting a finished download
     *   straight away, with no way back, is too easy to trigger by accident.
     *   [confirmDeleteDownload] does the actual removal once the user confirms.
     *
     * A separate "delete" affordance next to it would be dead most of the time, and "tap again
     * to undo" is what the same button already means everywhere else on this screen (watched,
     * favourite).
     *
     * On a **season or series** page every one of those meanings is about the episodes under it
     * rather than the item itself: enqueue expands the container in
     * `:data:downloads`, and remove/cancel act on each episode row. *Failed* on a container is
     * an enqueue too, not a resume — the container has no row of its own to put back in the
     * queue, and enqueueing is what retries the episodes that failed.
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
     * The delete-download dialog was confirmed — actually remove the item from this device.
     *
     * One row for a movie or an episode; for a season, every episode of it that has a row — the
     * ones that do not are skipped rather than deleted as no-ops, so cancelling a season that is
     * three episodes in does not run twenty pointless cascades through WorkManager.
     */
    fun confirmDeleteDownload() {
        state.update { it.copy(showDeleteConfirmation = false) }
        removeDownloads(
            targets = state.value.downloadTargets.filter { states.containsKey(it) },
            keptCount = 0,
        )
    }

    /** The delete-download dialog was dismissed without confirming — the download is untouched. */
    fun dismissDeleteConfirmation() {
        state.update { it.copy(showDeleteConfirmation = false) }
    }

    /**
     * Keeps the Download button — and the badge on every season, episode and related card —
     * in step with the pipeline.
     *
     * One subscription to the whole map rather than one per visible item, error-guarded so a
     * collapse clears the badges rather than freezing them — both rules, and why, live in
     * [observeBadgeStates].
     */
    private fun observeDownloadState() {
        scope.launch {
            downloads.observeBadgeStates(screen = "detail").collect { badges ->
                // Held so that a later load — which replaces every item in the state — can
                // re-apply them. `observeStates()` is distinct-until-changed and would not
                // re-emit just because this screen refetched.
                states = badges
                state.update { it.withDownloadStates(badges) }
            }
        }
    }

    /**
     * Keeps the metadata line's *N on device* figure in step with the download files actually
     * written, so a finished transfer's real footprint replaces the server's reported size
     * (`ItemDetailHeader.MetaRow`).
     *
     * A separate Room projection from [observeDownloadState] on purpose: this is a byte count, not
     * a status, and the container case ([DownloadState.Downloaded] aggregated from episodes) has no
     * row of its own to sum — that is what makes its bytes come back `null` and the header fall
     * back to the server size for a fully-downloaded series.
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
     * Cancels a download that is still in flight, **keeping whatever already finished**.
     *
     * On a season, Cancel must not run the same delete as Remove: a user stopping a season three
     * episodes in would lose those three. Cancel only touches rows that are queued, transferring,
     * paused or failed. A partly-kept season then aggregates back to *NotDownloaded* (the
     * deliberate some-episodes-missing behaviour), so the button offers *Download* for the rest;
     * removing the kept episodes goes through the Downloads screen's confirmed delete.
     */
    private fun cancelDownloads() {
        val targets = state.value.downloadTargets.mapNotNull { id -> states[id]?.let { id to it } }
        val (finished, inFlight) = targets.partition { (_, downloadState) -> downloadState is DownloadState.Downloaded }

        removeDownloads(targets = inFlight.map { (id, _) -> id }, keptCount = finished.size)
    }

    /**
     * Deletes [targets] and reports how it went. [keptCount] is the number of finished downloads
     * this call deliberately left alone, which is what the snackbar tells the user about.
     *
     * **One batch call, never a loop.** Every single delete stops the download worker and starts it
     * again, so cancelling a twenty-episode season one row at a time would be twenty stop/restart
     * cycles — and each restart hands the queue the next doomed episode, asking the server for a
     * transcode that the very next iteration cancels. `DownloadDao.requeueForUser` batches the
     * resume side for the same reason; `deleteAll` is the delete side of it, and it also runs the
     * metadata prune once instead of once per row.
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
