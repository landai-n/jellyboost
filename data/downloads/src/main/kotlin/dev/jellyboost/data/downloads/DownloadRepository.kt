package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import kotlinx.coroutines.flow.Flow

/**
 * Everything the UI can do with downloads — the only type `:feature:*` modules see from
 * `:data:downloads`. Every read is a Room `Flow` and nothing here caches: the screens are a projection
 * of the database and stay correct across process death by construction.
 */
interface DownloadRepository {
    /** Items with no download row are absent from the map; callers default to [DownloadState.NotDownloaded]. */
    fun observeStates(): Flow<Map<String, DownloadState>>

    /** Every download, queue order first — the Downloads screen's two tabs come from this one list. */
    fun observeDownloads(): Flow<List<DownloadItem>>

    fun observeStorage(): Flow<StorageUsage>

    /** Bytes [itemId] occupies on disk, or `null` when nothing of it is downloaded. */
    fun observeBytesOnDisk(itemId: String): Flow<Long?>

    fun observeStorageLocations(): Flow<StorageLocations>

    /**
     * Points future downloads at [volumeId]. **A location change is only allowed when no downloads
     * exist**, or when the caller has agreed to delete them all first: a finished download's file rows
     * hold absolute paths that nothing rewrites, so files left on the old volume would still be found —
     * until the card is pulled, at which point offline playback silently falls back to streaming.
     *
     * @param deleteExistingDownloads required when any download exists; the call fails otherwise, and
     *   nothing is changed.
     */
    suspend fun setStorageLocation(
        volumeId: String,
        deleteExistingDownloads: Boolean,
    ): AppResult<Unit>

    /** `true` while downloads are restricted to unmetered networks. */
    val wifiOnly: Flow<Boolean>

    suspend fun setWifiOnly(enabled: Boolean)

    /**
     * Re-fetches [itemId] in full, caches it and its parents as downloaded, queues it and makes sure
     * the queue is running. A container — season, series, album, artist, playlist — is expanded into
     * one download per child in the server's own order, skipping what is already on the device, so
     * callers never have to check the item's type.
     */
    suspend fun enqueue(itemId: String): AppResult<Unit>

    /** Stops the queue and marks [itemId] paused; its partial files stay on disk. */
    suspend fun pause(itemId: String): AppResult<Unit>

    suspend fun resume(itemId: String): AppResult<Unit>

    /**
     * Pauses several items at once. Not a loop over [pause], and that is the entire point: every
     * single-item mutation stops and restarts the WorkManager job, so a bulk action built out of them
     * issued forty overlapping `REPLACE` enqueues, each drain running `requeueInterrupted` over rows
     * another was still writing. Ids that name nothing are not matched; the call still succeeds.
     */
    suspend fun pauseAll(itemIds: List<String>): AppResult<Unit>

    /** Puts several paused or failed items back in the queue — one transition, one restart. */
    suspend fun resumeAll(itemIds: List<String>): AppResult<Unit>

    /**
     * Removes several items entirely. The queue is stopped **once**, before anything is unlinked, and
     * started again once at the end; the files still go one item at a time, each with its own cascade.
     *
     * @return total bytes freed on disk.
     */
    suspend fun deleteAll(itemIds: List<String>): AppResult<Long>

    /**
     * Removes [itemId] entirely — files, rows and orphaned metadata. The same operation backs *Cancel*
     * on a queued item and *Delete* on a finished one.
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
