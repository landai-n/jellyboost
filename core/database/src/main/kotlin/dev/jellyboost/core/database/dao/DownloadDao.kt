package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.database.entities.DownloadWithFiles
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions")
@Dao
interface DownloadDao {
    @Transaction
    @Query("SELECT * FROM downloads ORDER BY queuePosition ASC, createdAt DESC")
    fun observeAll(): Flow<List<DownloadWithFiles>>

    /** Re-emitted on every throttled progress write and observed by every card — keep it a cheap projection. */
    @Query("SELECT itemId, status, bytesDownloaded, bytesTotal FROM downloads")
    fun observeProgress(): Flow<List<DownloadProgress>>

    @Query("SELECT SUM(bytesDownloaded) FROM download_files WHERE itemId = :itemId")
    fun observeBytesOnDisk(itemId: UUID): Flow<Long?>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun get(itemId: UUID): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE itemId IN (:itemIds)")
    suspend fun getAll(itemIds: List<UUID>): List<DownloadEntity>

    @Transaction
    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun getWithFiles(itemId: UUID): DownloadWithFiles?

    /** `DOWNLOADING` is in the filter on purpose: such a row died mid-file, and Range resume is what picks it up. */
    @Transaction
    @Query(
        """
        SELECT * FROM downloads
        WHERE status IN ('QUEUED', 'DOWNLOADING')
        ORDER BY queuePosition ASC, createdAt ASC
        LIMIT 1
        """,
    )
    suspend fun nextRunnable(): DownloadWithFiles?

    @Query("SELECT itemId FROM downloads")
    suspend fun allItemIds(): List<UUID>

    @Query("SELECT directoryName FROM downloads")
    suspend fun allDirectoryNames(): List<String>

    @Query("SELECT MAX(queuePosition) FROM downloads")
    suspend fun maxQueuePosition(): Int?

    @Query(
        """
        SELECT * FROM downloads
        WHERE seriesName = :seriesName AND quality = :quality AND status = 'DOWNLOADED'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun completedSiblings(
        seriesName: String,
        quality: DownloadQuality,
        limit: Int,
    ): List<DownloadEntity>

    @Query(
        """
        SELECT * FROM downloads
        WHERE seriesName = :seriesName AND quality = :quality
          AND status IN ('QUEUED', 'PAUSED')
          AND projectedBytes IS NULL AND sizeIsExact = 0
        ORDER BY queuePosition ASC
        """,
    )
    suspend fun unseededSiblings(
        seriesName: String,
        quality: DownloadQuality,
    ): List<DownloadEntity>

    /**
     * The `projectedBytes IS NULL` test must stay in the statement: a live `TranscodeSizeProjector`
     * measurement can land through [updateProgress] while this seed is still being computed, and a
     * measurement has to outrank a guess by construction. `bytesTotal` is deliberately never written here.
     */
    @Query(
        "UPDATE downloads SET projectedBytes = :projectedBytes, updatedAt = :updatedAt " +
            "WHERE itemId = :itemId AND projectedBytes IS NULL",
    )
    suspend fun setProjectedBytesIfAbsent(
        itemId: UUID,
        projectedBytes: Long,
        updatedAt: Instant,
    )

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') ORDER BY queuePosition ASC")
    suspend fun pending(): List<DownloadEntity>

    /**
     * The `itemType IS NULL` test must stay in the statement: this runs on a background refresh while the
     * queue is writing, so reading the row first and deciding outside SQL would let an enqueue land in
     * between and have its columns overwritten from a stale server fetch.
     */
    @Query(
        """
        UPDATE downloads
        SET itemType = :type, seriesName = :seriesName, albumName = :albumName, groupId = :groupId
        WHERE itemId = :itemId AND itemType IS NULL
        """,
    )
    suspend fun backfillGrouping(
        itemId: UUID,
        type: ItemType,
        seriesName: String?,
        albumName: String?,
        groupId: UUID?,
    )

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    /**
     * The status test must stay in the statement. A cancel writes `CANCELLED`, the UI immediately offers
     * **Download** again, and a re-tap inside the cascade's stop window creates a fresh `QUEUED` row; an
     * unguarded delete removed *that* row and its files. `QUEUED`/`DOWNLOADING` at cascade time therefore
     * means a new owner. Callers claim their targets with [demoteRunnable] first; this re-checks the claim.
     *
     * @return `1` when the row was removed, `0` when it was left to its new owner.
     */
    @Query("DELETE FROM downloads WHERE itemId = :itemId AND status NOT IN ('QUEUED', 'DOWNLOADING')")
    suspend fun deleteUnlessRunnable(itemId: UUID): Int

    @Query(
        "UPDATE downloads SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt " +
            "WHERE itemId = :itemId",
    )
    suspend fun setStatus(
        itemId: UUID,
        status: DownloadStatus,
        updatedAt: Instant,
        errorMessage: String? = null,
    )

    /**
     * The status test must stay in the statement: pause writes `PAUSED` *before* stopping the worker, and an
     * unconditional claim would overwrite it and download the item the user just paused. `0` means the row
     * changed hands (paused, cancelled, deleted) since it was picked — skip it, do not transfer.
     */
    @Query(
        "UPDATE downloads SET status = 'DOWNLOADING', errorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE itemId = :itemId AND status IN ('QUEUED', 'DOWNLOADING')",
    )
    suspend fun markDownloadingIfRunnable(
        itemId: UUID,
        updatedAt: Instant,
    ): Int

    /**
     * Must stay one transaction: a read of "is a target `DOWNLOADING`?" followed by a separate write lets
     * [markDownloadingIfRunnable] claim the row in between, so the caller skips stopping the worker and the
     * unguarded write buries the evidence — the item transfers to completion with nothing left to stop it.
     */
    @Transaction
    suspend fun demoteRunnable(
        itemIds: List<UUID>,
        status: DownloadStatus,
        updatedAt: Instant,
    ): Boolean {
        val tookLiveTransfer = setStatusIfDownloading(itemIds, status, updatedAt) > 0
        setStatusIfQueued(itemIds, status, updatedAt)
        return tookLiveTransfer
    }

    @Query(
        "UPDATE downloads SET status = :status, errorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE itemId IN (:itemIds) AND status = 'DOWNLOADING'",
    )
    suspend fun setStatusIfDownloading(
        itemIds: List<UUID>,
        status: DownloadStatus,
        updatedAt: Instant,
    ): Int

    @Query(
        "UPDATE downloads SET status = :status, errorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE itemId IN (:itemIds) AND status = 'QUEUED'",
    )
    suspend fun setStatusIfQueued(
        itemIds: List<UUID>,
        status: DownloadStatus,
        updatedAt: Instant,
    ): Int

    /**
     * Clearing [DownloadEntity.attemptCount] is why this is not a plain status write: a row that exhausted
     * its retry budget must get a full one back, or *Retry* would be worth exactly one attempt. One statement
     * for the whole batch — per-row mutations would stop/restart the WorkManager job once per row.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attemptCount = 0, " +
            "updatedAt = :updatedAt WHERE itemId IN (:itemIds)",
    )
    suspend fun requeueForUser(
        itemIds: List<UUID>,
        updatedAt: Instant,
    )

    /** The status test keeps this from resurrecting an item cancelled or paused while it was in flight. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attemptCount = :attemptCount, " +
            "updatedAt = :updatedAt WHERE itemId = :itemId AND status = 'DOWNLOADING'",
    )
    suspend fun requeueForRetry(
        itemId: UUID,
        attemptCount: Int,
        updatedAt: Instant,
    )

    @Query("UPDATE downloads SET attemptCount = 0 WHERE itemId = :itemId")
    suspend fun clearAttempts(itemId: UUID)

    /**
     * Runs up to twice a second for the length of a multi-gigabyte transfer, so it stays a targeted `UPDATE`
     * that cannot race the queue's status writes. A `null` [projectedBytes] clears the projection.
     */
    @Query(
        "UPDATE downloads SET bytesDownloaded = :bytesDownloaded, bytesTotal = :bytesTotal, " +
            "projectedBytes = :projectedBytes, updatedAt = :updatedAt WHERE itemId = :itemId",
    )
    suspend fun updateProgress(
        itemId: UUID,
        bytesDownloaded: Long,
        bytesTotal: Long,
        projectedBytes: Long?,
        updatedAt: Instant,
    )

    @Query("UPDATE downloads SET queuePosition = :position, updatedAt = :updatedAt WHERE itemId = :itemId")
    suspend fun setQueuePosition(
        itemId: UUID,
        position: Int,
        updatedAt: Instant,
    )

    /**
     * Run at worker start; the `WHERE` clause is the point — a paused, finished or failed row is not
     * interrupted and must keep the status it was given.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', updatedAt = :updatedAt WHERE status = 'DOWNLOADING'",
    )
    suspend fun requeueInterrupted(updatedAt: Instant)

    /**
     * The status test must stay in the statement: *Pause* writes `PAUSED` and *then* cancels the work, so an
     * unconditional requeue here overwrites it and [nextRunnable] picks the item straight back up.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', updatedAt = :updatedAt " +
            "WHERE itemId = :itemId AND status = 'DOWNLOADING'",
    )
    suspend fun requeueIfDownloading(
        itemId: UUID,
        updatedAt: Instant,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFile(file: DownloadFileEntity): Long

    @Update
    suspend fun updateFile(file: DownloadFileEntity)

    @Query(
        "UPDATE download_files SET bytesDownloaded = :bytesDownloaded, bytesTotal = :bytesTotal " +
            "WHERE id = :fileId",
    )
    suspend fun updateFileProgress(
        fileId: Long,
        bytesDownloaded: Long,
        bytesTotal: Long,
    )

    @Query("UPDATE download_files SET status = :status WHERE id = :fileId")
    suspend fun setFileStatus(
        fileId: Long,
        status: DownloadStatus,
    )

    /** Keeps a `UserDataEntity` that still owes the server a change; the cascade drops only synced rows. */
    @Query("DELETE FROM user_data WHERE itemId IN (:itemIds) AND toBeSynced = 0")
    suspend fun deleteSyncedUserData(itemIds: List<UUID>)
}
