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
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.database.entities.DownloadWithFiles
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

    /**
     * One item's footprint on disk, or `null` when it has no file rows at all.
     *
     * A projection rather than [getWithFiles] or [observeAll]: the detail screen only wants a
     * single number for one item, and dragging a `@Transaction` over the whole `DownloadWithFiles`
     * shape through Room for that would be paying for rows nobody reads. The sum is the same figure
     * the Downloads tab shows via `DownloadWithFiles.bytesOnDisk`.
     */
    @Query("SELECT SUM(bytesDownloaded) FROM download_files WHERE itemId = :itemId")
    fun observeBytesOnDisk(itemId: UUID): Flow<Long?>

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

    /**
     * Every item directory a download row still claims — the input to the orphan **file** sweep.
     *
     * Distinct from [allItemIds] because the thing on disk is keyed by name, not by id: the sweep
     * has to decide whether a directory it found belongs to anything, and the row that would answer
     * that may already be gone (docs/notes/audit-2026-07.md, STAB-04).
     */
    @Query("SELECT directoryName FROM downloads")
    suspend fun allDirectoryNames(): List<String>

    /** Highest queue position in use, or `null` when the queue is empty. */
    @Query("SELECT MAX(queuePosition) FROM downloads")
    suspend fun maxQueuePosition(): Int?

    /**
     * Finished downloads of the same show at the same quality, newest first — the ground truth a
     * fresh episode's size is seeded from (`DownloadEnqueuer`, schema v6).
     *
     * `seriesName` rather than a series id because that is the column this table actually carries;
     * it is denormalised from the item precisely so a download row can be reasoned about without
     * decoding a `BaseItemDto`. Two shows sharing a name on one server is not a case worth a join.
     *
     * The quality filter is not optional: a season half-downloaded at `ORIGINAL` says nothing about
     * what its remaining episodes will weigh at `LOW`.
     */
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

    /**
     * Rows of the same show at the same quality that are still waiting and still have nothing
     * better than their ceiling to show — what a finished sibling re-seeds (`SiblingSeeder`).
     *
     * Every clause is a case that must not be touched: a `DOWNLOADED` or `ERROR` row is not waiting
     * for a size, a row that already carries a `projectedBytes` has a better answer than a fresh
     * guess (a live scanner measurement, or an earlier seed), and a row whose `bytesTotal` is
     * already exact has no ceiling to improve on.
     */
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
     * Writes a projection onto a row that has none, and leaves one that has alone.
     *
     * The `projectedBytes IS NULL` test lives in the statement rather than in a read-then-write in
     * Kotlin because the two racers are real: the queue starts the row it has just re-seeded and
     * its `TranscodeSizeProjector` begins writing measurements through
     * [updateProgress], while this seed is still being computed from the rows of its siblings. A
     * measurement outranks a guess, so the guess must lose that race by construction.
     *
     * [bytesTotal] is deliberately not in the statement: the ceiling is what the enqueue step
     * promised, and a seed is only ever allowed to improve on what is shown *next to* it.
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
     * Claims a row the drain is about to transfer, and says whether the claim took.
     *
     * The status test lives in the statement for the same reason [requeueIfDownloading]'s does:
     * the racer is real. `pause`/`pauseAll` write `PAUSED` *before* stopping the worker, and a
     * drain sitting between `nextRunnable()` and the start of the transfer used to write
     * `DOWNLOADING` unconditionally over it — the cancellation then hit `requeueIfDownloading`,
     * which put the row back to `QUEUED`, and the next drain downloaded the item the user had just
     * paused (audit DL-03). A return of `0` means the row changed hands (paused, cancelled,
     * deleted) since it was picked, and the item must be skipped, not transferred.
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
     * Takes rows out of the queue's reach, and says whether the live transfer was among them.
     *
     * One transaction on purpose, and the *only* rule this DAO carries. Pause and delete used to
     * decide in two DB calls — read "is a target `DOWNLOADING`?", then write the new status — and
     * the drain's [markDownloadingIfRunnable] claim could land in between: the read saw `QUEUED`
     * (so the caller chose not to stop the worker), the claim took the row, and the unguarded
     * write buried the evidence. The item the user had just paused (or was deleting out from
     * under the worker) then transferred to completion with nothing left to stop it — the same
     * window as audit DL-03, reopened on the caller's side by the DL-06 stop-elision. Inside a
     * transaction the claim must land either before (the `DOWNLOADING` leg takes the row and the
     * caller stops the worker) or after (the claim's own status guard sees [status] and refuses).
     *
     * Only `QUEUED` and `DOWNLOADING` rows are touched: they are the two statuses the drain can
     * pick up, and a finished, failed or already-paused row is not being taken from anyone.
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

    /** The `DOWNLOADING` leg of [demoteRunnable]; the count is its return value. */
    @Query(
        "UPDATE downloads SET status = :status, errorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE itemId IN (:itemIds) AND status = 'DOWNLOADING'",
    )
    suspend fun setStatusIfDownloading(
        itemIds: List<UUID>,
        status: DownloadStatus,
        updatedAt: Instant,
    ): Int

    /** The `QUEUED` leg of [demoteRunnable]. */
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
     * Puts several rows back in the queue at the user's request — *Resume all*, and *Retry*.
     *
     * Clearing [DownloadEntity.attemptCount] is the point of having a separate statement from a
     * plain status write: a row that exhausted its retry budget must get a full one back when the
     * user asks for it again, or *Retry* would be worth exactly one attempt.
     *
     * Like [demoteRunnable]'s legs it is one statement for the whole batch, and that batching is
     * not a micro-optimisation: a bulk action used to issue one repository mutation per row, and
     * each of those stopped and restarted the WorkManager job, so a forty-episode queue produced
     * forty stop/start cycles and as many overlapping drains (docs/notes/audit-2026-07.md,
     * STAB-09). One statement, one transition, one restart.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attemptCount = 0, " +
            "updatedAt = :updatedAt WHERE itemId IN (:itemIds)",
    )
    suspend fun requeueForUser(
        itemIds: List<UUID>,
        updatedAt: Instant,
    )

    /**
     * Puts a row that failed *transiently* back in the queue, counting the attempt.
     *
     * The status test is what keeps this from resurrecting an item the user cancelled or paused
     * while it was in flight — the same race [requeueIfDownloading] guards, for the same reason.
     * The error message is cleared because the row is not failed: it is waiting for the next run.
     */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attemptCount = :attemptCount, " +
            "updatedAt = :updatedAt WHERE itemId = :itemId AND status = 'DOWNLOADING'",
    )
    suspend fun requeueForRetry(
        itemId: UUID,
        attemptCount: Int,
        updatedAt: Instant,
    )

    /** Gives one row its full retry budget back — what a user-initiated *Resume* means. */
    @Query("UPDATE downloads SET attemptCount = 0 WHERE itemId = :itemId")
    suspend fun clearAttempts(itemId: UUID)

    /**
     * Writes one throttled progress sample.
     *
     * Deliberately a targeted `UPDATE` rather than a read-modify-write of the whole row: this runs
     * up to twice a second for the length of a multi-gigabyte transfer, and it must not race the
     * status writes coming from the queue.
     *
     * [projectedBytes] rides along in the same statement rather than getting a write of its own, so
     * a sample is still one round trip; `null` clears it, which is what the end of the media file
     * does once the real size is known.
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
