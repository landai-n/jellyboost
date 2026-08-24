package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.database.entities.UserDataEntity
import java.time.Instant
import java.util.UUID

@Dao
interface UserDataDao {
    @Upsert
    suspend fun upsert(userData: UserDataEntity)

    /**
     * Callers refreshing rows from a server read **must** filter the list against [getPendingSyncIds] first:
     * this replaces rows unconditionally, and overwriting a `toBeSynced = true` row drops a local change the
     * server has never seen.
     */
    @Upsert
    suspend fun upsertAll(userData: List<UserDataEntity>)

    /**
     * The guard the browse-cache refresh reads before adopting server user data: a pending row is the only
     * copy of that change, so the server's older value must not land on top of it.
     */
    @Query(
        "SELECT itemId FROM user_data " +
            "WHERE userId = :userId AND itemId IN (:itemIds) AND toBeSynced = 1",
    )
    suspend fun getPendingSyncIds(
        itemIds: List<UUID>,
        userId: UUID,
    ): List<UUID>

    @Query("SELECT * FROM user_data WHERE itemId = :itemId AND userId = :userId")
    suspend fun getUserData(
        itemId: UUID,
        userId: UUID,
    ): UserDataEntity?

    /**
     * **One-shot on purpose, and this DAO deliberately exposes no `Flow` at all.** Screens are patched by
     * `UserDataEventBus`, which broadcasts the optimistic write that has *already* landed; a Room
     * subscription per open page would re-read this table on every progress tick to re-deliver the same thing.
     */
    @Query("SELECT * FROM user_data WHERE userId = :userId AND itemId IN (:itemIds)")
    suspend fun getUserDataFor(
        itemIds: List<UUID>,
        userId: UUID,
    ): List<UserDataEntity>

    @Query("SELECT * FROM user_data WHERE toBeSynced = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<UserDataEntity>

    @Query("SELECT COUNT(*) FROM user_data WHERE toBeSynced = 1")
    suspend fun countPendingSync(): Int

    /**
     * The `updatedAt <= :syncedAt` guard makes this safe after a slow push: if the user toggled the same item
     * again while the request was in flight, the row is newer than what the server accepted and keeps its flag.
     *
     * @return the number of rows cleared (0 when a newer local write superseded this one).
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
     * Keeps the rows still pending: a synced row is pure cache, while a pending one is the only copy of a
     * change the server has not seen and must survive to be pushed when the account signs back in. (The
     * per-item version of the rule is `DownloadDao.deleteSyncedUserData`.)
     */
    @Query("DELETE FROM user_data WHERE userId = :userId AND toBeSynced = 0")
    suspend fun deleteSynced(userId: UUID)
}
