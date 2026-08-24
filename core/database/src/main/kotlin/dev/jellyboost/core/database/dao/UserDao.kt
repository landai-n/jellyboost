package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.database.entities.UserEntity
import java.util.UUID

/** Never stores access tokens — see [UserEntity]. */
@Dao
interface UserDao {
    @Upsert
    suspend fun upsertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUser(id: UUID): UserEntity?

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    suspend fun getUsersForServer(serverId: UUID): List<UserEntity>

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUser(id: UUID)
}
