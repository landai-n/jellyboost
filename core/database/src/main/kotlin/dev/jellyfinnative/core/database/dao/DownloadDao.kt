package dev.jellyfinnative.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadProgress
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * Data access for the download schema (docs/PLAN.md, "Download pipeline").
 *
 * Like [ItemDao] this DAO is deliberately dumb — queries and nothing else. Every rule worth
 * testing (which item runs next, what a delete cascade prunes, how progress is throttled) lives in
 * `:data:downloads` so it can be exercised on the JVM instead of only on a device.
 *
 * Two shapes of read exist on purpose:
 * - [observeAll] returns whole rows with their files; it backs the Downloads screen, which is one
 *   screen and can afford it.
 * - [observeProgress] returns a four-column projection; it backs the `DownloadBadge` on *every*
 *   card in the app and is re-emitted on every throttled progress write, so it must stay cheap.
 */
@Suppress("TooManyFunctions")
@Dao
interface DownloadDao {
    // ---- reads ---------------------------------------------------------------------------------

    /** Every download with its files, queue order first, then most recently enqueued. */
    @Transaction
    @Query("SELECT * FROM downloads ORDER BY queuePosition ASC, createdAt DESC")
    fun observeAll(): Flow<List<DownloadWithFiles>>

    /** The columns the app-wide download badges need, for every known item. */
    @Query("SELECT itemId, status, bytesDownloaded, bytesTotal FROM downloads")
    fun observeProgress(): Flow<List<DownloadProgress>>

    /** One download row without its files. */
    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun get(itemId: UUID): DownloadEntity?

    /**
     * One download **with** its files — what offline playback resolves against (M8).
     *
     * [observeAll] returns the same shape for the whole table; a player opening a single item has
     * no use for the other rows and no use for a Flow, since the file set of a finished download
     * does not change while it is being played.
     */
    @Transaction
    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun getWithFiles(itemId: UUID): DownloadWithFiles?

    /**
     * The next item the queue should work on: the lowest queue position among rows that are
     * waiting or were interrupted mid-transfer.
     *
     * [DownloadStatus.DOWNLOADING] is included deliberately. A row left in that state is one whose
     * process died mid-file; picking it up again is exactly what the plan's Range resume is for.
     */
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

    /** Every item id that still has a download row — the input to the orphan prune. */
    @Query("SELECT itemId FROM downloads")
    suspend fun allItemIds(): List<UUID>

    /** Highest queue position in use, or `null` when the queue is empty. */
    @Query("SELECT MAX(queuePosition) FROM downloads")
    suspend fun maxQueuePosition(): Int?

    /** Rows in queue order — what a reorder renumbers. */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') ORDER BY queuePosition ASC")
    suspend fun pending(): List<DownloadEntity>

    // ---- writes --------------------------------------------------------------------------------

    /** Inserts the download, or replaces the existing row for the same item. */
    @Upsert
    suspend fun upsert(download: DownloadEntity)

    /** Removes one download; its `download_files` rows go with it through the foreign key. */
    @Query("DELETE FROM downloads WHERE itemId = :itemId")
    suspend fun delete(itemId: UUID)

    /** Moves a download to a new status, stamping [updatedAt] and clearing any stale error. */
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
     * Writes one throttled progress sample.
     *
     * Deliberately a targeted `UPDATE` rather than a read-modify-write of the whole row: this runs
     * up to twice a second for the length of a multi-gigabyte transfer, and it must not race the
     * status writes coming from the queue.
     */
    @Query(
        "UPDATE downloads SET bytesDownloaded = :bytesDownloaded, bytesTotal = :bytesTotal, " +
            "updatedAt = :updatedAt WHERE itemId = :itemId",
    )
    suspend fun updateProgress(
        itemId: UUID,
        bytesDownloaded: Long,
        bytesTotal: Long,
        updatedAt: Instant,
    )

    /** Renumbers one row's place in the queue. */
    @Query("UPDATE downloads SET queuePosition = :position, updatedAt = :updatedAt WHERE itemId = :itemId")
    suspend fun setQueuePosition(
        itemId: UUID,
        position: Int,
        updatedAt: Instant,
    )

    /**
     * Puts every interrupted row back in the queue.
     *
     * Run when the download worker starts: a row still marked `DOWNLOADING` from a previous process
     * would otherwise be indistinguishable from the one the current worker is actually running.
     *
     * The `WHERE` clause is the whole point: a row the user paused (or one that finished, or
     * failed) is *not* interrupted, and must keep the status it was given.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', updatedAt = :updatedAt WHERE status = 'DOWNLOADING'",
    )
    suspend fun requeueInterrupted(updatedAt: Instant)

    /**
     * Puts **one** row back in the queue, and only while it is still transferring.
     *
     * What the queue runs when its coroutine is cancelled. The status test belongs in the statement
     * rather than in a read-then-write in Kotlin because the two racers are exactly a user pressing
     * *Pause* — which writes `PAUSED` and *then* cancels the work — and this handler reacting to
     * that cancellation. An unconditional write here overwrites the `PAUSED` the user asked for
     * with `QUEUED`, and `nextRunnable` picks the item straight back up (docs/POLISH.md,
     * "pausing a download doesn't work").
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', updatedAt = :updatedAt " +
            "WHERE itemId = :itemId AND status = 'DOWNLOADING'",
    )
    suspend fun requeueIfDownloading(
        itemId: UUID,
        updatedAt: Instant,
    )

    // ---- files ---------------------------------------------------------------------------------

    /** Inserts a planned file, returning its generated id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFile(file: DownloadFileEntity): Long

    /** Updates a file row in place. */
    @Update
    suspend fun updateFile(file: DownloadFileEntity)

    /** Writes one file's transfer progress. */
    @Query(
        "UPDATE download_files SET bytesDownloaded = :bytesDownloaded, bytesTotal = :bytesTotal " +
            "WHERE id = :fileId",
    )
    suspend fun updateFileProgress(
        fileId: Long,
        bytesDownloaded: Long,
        bytesTotal: Long,
    )

    /** Moves one file to a new status. */
    @Query("UPDATE download_files SET status = :status WHERE id = :fileId")
    suspend fun setFileStatus(
        fileId: Long,
        status: DownloadStatus,
    )

    // ---- delete cascade ------------------------------------------------------------------------

    /**
     * Drops the local user-data row for a deleted download **unless it still owes the server a
     * change** (docs/PLAN.md, "Delete cascade": "keep `UserDataEntity` only if `toBeSynced`").
     *
     * It queries `user_data` from the download DAO on purpose. The rule is part of *this* cascade
     * and nothing else uses it, and M7 was built alongside a parallel branch that owns
     * [UserDataDao] — putting the statement here kept the two changes from colliding over one file.
     */
    @Query("DELETE FROM user_data WHERE itemId = :itemId AND toBeSynced = 0")
    suspend fun deleteSyncedUserData(itemId: UUID)
}
