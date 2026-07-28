package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.data.downloads.model.DownloadItem
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

    /** `true` while downloads are restricted to unmetered networks. */
    val wifiOnly: Flow<Boolean>

    /** Turns the Wi-Fi-only restriction on or off and re-applies it to the running queue. */
    suspend fun setWifiOnly(enabled: Boolean)

    /**
     * Re-fetches [itemId] in full, caches it (and its parents) as downloaded, queues it, and makes
     * sure the queue is running.
     */
    suspend fun enqueue(itemId: String): AppResult<Unit>

    /** Stops the queue and marks [itemId] paused; its partial files stay on disk. */
    suspend fun pause(itemId: String): AppResult<Unit>

    /** Puts a paused or failed item back in the queue and restarts the worker. */
    suspend fun resume(itemId: String): AppResult<Unit>

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
