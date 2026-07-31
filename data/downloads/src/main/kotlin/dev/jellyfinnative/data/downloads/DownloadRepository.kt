package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageLocations
import dev.jellyfinnative.data.downloads.model.StorageUsage
import kotlinx.coroutines.flow.Flow

/**
 * Everything the UI can do with downloads.
 *
 * This is the only type `:feature:*` modules see from `:data:downloads`; the queue, the worker, the
 * file plan and the storage backend are all implementation details behind it.
 *
 * Every read is a **Room Flow**, because Room is the single source of truth for download state
 * (docs/PLAN.md, "Download pipeline" → Progress). Nothing here caches; the screens are a projection
 * of the database and stay correct across process death by construction.
 */
interface DownloadRepository {
    /**
     * Download state keyed by item id — what every `DownloadBadge` in the app renders from
     * (docs/PLAN.md: "Every item card shows `DownloadBadge` from `DownloadDao.observeStatusMap()`").
     *
     * Items with no download row are simply absent from the map; callers default to
     * [DownloadState.NotDownloaded].
     */
    fun observeStates(): Flow<Map<String, DownloadState>>

    /** Every download, queue order first — the Downloads screen's two tabs come from this one list. */
    fun observeDownloads(): Flow<List<DownloadItem>>

    /** Storage used and free at the download root; re-read whenever [observeDownloads] changes. */
    fun observeStorage(): Flow<StorageUsage>

    /** Bytes [itemId] occupies on disk, or `null` when nothing of it is downloaded. */
    fun observeBytesOnDisk(itemId: String): Flow<Long?>

    /** The volumes downloads can be written to, and which one is in force — the Settings picker. */
    fun observeStorageLocations(): Flow<StorageLocations>

    /**
     * Points future downloads at [volumeId].
     *
     * The plan's v1 policy is enforced here rather than in the UI: **a location change is only
     * allowed when no downloads exist**, or when the caller has agreed to delete them all first
     * (docs/PLAN.md, "Download pipeline" → Storage; `MoveStorageWorker` is deferred). The reason is
     * mechanical, not cautious: a finished download's file rows hold absolute paths that nothing
     * rewrites, so files left on the old volume would still be found — until the card is pulled, at
     * which point offline playback would silently fall back to streaming.
     *
     * @param deleteExistingDownloads deletes every download — files, rows and orphaned metadata —
     *   before switching. Required when any download exists; the call fails otherwise, and nothing
     *   is changed.
     */
    suspend fun setStorageLocation(
        volumeId: String,
        deleteExistingDownloads: Boolean,
    ): AppResult<Unit>

    /** `true` while downloads are restricted to unmetered networks. */
    val wifiOnly: Flow<Boolean>

    /** Turns the Wi-Fi-only restriction on or off and re-applies it to the running queue. */
    suspend fun setWifiOnly(enabled: Boolean)

    /**
     * Re-fetches [itemId] in full, caches it (and its parents) as downloaded, queues it, and makes
     * sure the queue is running.
     *
     * A **season or a series** is a folder with no file behind it, so it is expanded instead: one
     * download per episode, in broadcast order, skipping the episodes already on the device
     * (DECISIONS.md, 2026-07-29). Callers therefore never have to check the item's type.
     */
    suspend fun enqueue(itemId: String): AppResult<Unit>

    /** Stops the queue and marks [itemId] paused; its partial files stay on disk. */
    suspend fun pause(itemId: String): AppResult<Unit>

    /** Puts a paused or failed item back in the queue and restarts the worker. */
    suspend fun resume(itemId: String): AppResult<Unit>

    /**
     * Pauses several items at once — *Pause all*.
     *
     * Not a loop over [pause], and that is the entire point. Every single-item mutation stops and
     * restarts the WorkManager job, so a bulk action built out of them issued one stop/start cycle
     * per row: forty overlapping `REPLACE` enqueues, each starting a drain while the previous one
     * was still unwinding, and each of those drains running `requeueInterrupted` over rows another
     * drain was still writing (docs/notes/audit-2026-07.md, STAB-09). This writes one status
     * transition and touches the scheduler once.
     *
     * Ids that name nothing are simply not matched; the call still succeeds.
     */
    suspend fun pauseAll(itemIds: List<String>): AppResult<Unit>

    /** Puts several paused or failed items back in the queue — one transition, one restart. */
    suspend fun resumeAll(itemIds: List<String>): AppResult<Unit>

    /**
     * Removes several items entirely — *Cancel all*, and the queue half of a storage switch.
     *
     * The queue is stopped **once**, before anything is unlinked, and started again once at the
     * end. Files have to go one item at a time (each has its own directory and its own cascade),
     * but the scheduler churn a per-item loop produced does not.
     *
     * @return total bytes freed on disk.
     */
    suspend fun deleteAll(itemIds: List<String>): AppResult<Long>

    /**
     * Removes [itemId] entirely — files, rows and orphaned metadata.
     *
     * The same operation backs *Cancel* on a queued item and *Delete* on a finished one: both mean
     * "I do not want this on the device", and a half-downloaded file is not worth a second code
     * path.
     *
     * @return bytes freed on disk.
     */
    suspend fun delete(itemId: String): AppResult<Long>

    /** Moves a pending item to [position] in the queue, renumbering the rest. */
    suspend fun move(
        itemId: String,
        position: Int,
    ): AppResult<Unit>
}
