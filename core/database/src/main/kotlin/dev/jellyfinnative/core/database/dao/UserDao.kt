package dev.jellyfinnative.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyfinnative.core.database.entities.UserEntity
import java.util.UUID

/** Data access for [UserEntity]. Never stores access tokens — see [UserEntity] KDoc. */
@Dao
interface UserDao {
    /** Inserts [user], or updates it in place if a row with the same id already exists. */
    @Upsert
    suspend fun upsertUser(user: UserEntity)

    /** Returns the user with [id], or `null` if none is stored. */
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUser(id: UUID): UserEntity?

    /** Returns every user that has signed in on the server with id [serverId]. */
    @Query("SELECT * FROM users WHERE serverId = :serverId")
    suspend fun getUsersForServer(serverId: UUID): List<UserEntity>

    /** Deletes the user with [id]. */
    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUser(id: UUID)
}
