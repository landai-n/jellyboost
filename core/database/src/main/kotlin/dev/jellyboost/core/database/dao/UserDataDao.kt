package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.database.entities.UserDataEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/** Data access for [UserDataEntity] — the local-first user-data table. */
@Dao
interface UserDataDao {
    /** Inserts the row, or replaces the existing `(itemId, userId)` row in place. */
    @Upsert
    suspend fun upsert(userData: UserDataEntity)

    /**
     * Batch counterpart of [upsert] — one statement for a whole page of server-refreshed rows.
     *
     * Deliberately *not* an overload of [upsert]: `upsert(any())` appears all over the existing
     * tests and would become ambiguous, which is a poor trade for a shared name.
     *
     * Callers refreshing rows from a server read **must** filter the list against
     * [getPendingSyncIds] first: this replaces rows unconditionally, and overwriting a
     * `toBeSynced = true` row would drop a local change the server has never seen.
     */
    @Upsert
    suspend fun upsertAll(userData: List<UserDataEntity>)

    /**
     * Of [itemIds], the ones whose row still holds an unpushed local change.
     *
     * This is the guard the browse-cache refresh reads before adopting server user data: a pending
     * row is the only copy of that change, so the server's (older) value must not land on top of it
     * — see `BrowseCacheWriter`.
     */
    @Query(
        "SELECT itemId FROM user_data " +
            "WHERE userId = :userId AND itemId IN (:itemIds) AND toBeSynced = 1",
    )
    suspend fun getPendingSyncIds(
        itemIds: List<UUID>,
        userId: UUID,
    ): List<UUID>

    /** Returns the stored row for one item, or `null` when this device has never written one. */
    @Query("SELECT * FROM user_data WHERE itemId = :itemId AND userId = :userId")
    suspend fun getUserData(
        itemId: UUID,
        userId: UUID,
    ): UserDataEntity?

    /** Observes one item's row; emits `null` while no row exists. */
    @Query("SELECT * FROM user_data WHERE itemId = :itemId AND userId = :userId")
    fun observeUserData(
        itemId: UUID,
        userId: UUID,
    ): Flow<UserDataEntity?>

    /**
     * Observes the rows that exist for [itemIds] — the query behind an offline list screen
     * overlaying local playback state onto cached items.
     */
    @Query("SELECT * FROM user_data WHERE userId = :userId AND itemId IN (:itemIds)")
    fun observeUserDataFor(
        itemIds: List<UUID>,
        userId: UUID,
    ): Flow<List<UserDataEntity>>

    /**
     * One-shot counterpart of [observeUserDataFor] (M6).
     *
     * The offline repository overlays local playback state onto a page of cached items it has just
     * read; a Flow there would mean a second subscription per page for a value it reads once.
     */
    @Query("SELECT * FROM user_data WHERE userId = :userId AND itemId IN (:itemIds)")
    suspend fun getUserDataFor(
        itemIds: List<UUID>,
        userId: UUID,
    ): List<UserDataEntity>

    /** Every row still waiting to reach the server, oldest first — the sync worker's work list. */
    @Query("SELECT * FROM user_data WHERE toBeSynced = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<UserDataEntity>

    /** How many rows are waiting to reach the server. */
    @Query("SELECT COUNT(*) FROM user_data WHERE toBeSynced = 1")
    suspend fun countPendingSync(): Int

    /**
     * Clears the pending flag after the server accepted the value written at [syncedAt].
     *
     * The `updatedAt <= :syncedAt` guard is what makes this safe to run after a slow network push:
     * if the user toggled the same item again while the request was in flight, the row is newer
     * than what the server just accepted and keeps its flag.
     *
     * @return the number of rows actually cleared (0 when a newer local write superseded this one).
     */
    @Query(
        "UPDATE user_data SET toBeSynced = 0 " +
            "WHERE itemId = :itemId AND userId = :userId AND updatedAt <= :syncedAt",
    )
    suspend fun clearPendingSync(
        itemId: UUID,
        userId: UUID,
        syncedAt: Instant,
    ): Int

    /**
     * Drops every fully-synced row for a user, keeping the ones still pending.
     *
     * Used by the download-delete cascade (M7) and by sign-out: a synced row is pure cache, a
     * pending one is the only copy of a change the server has not seen.
     */
    @Query("DELETE FROM user_data WHERE userId = :userId AND toBeSynced = 0")
    suspend fun deleteSynced(userId: UUID)
}
