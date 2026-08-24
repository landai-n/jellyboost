package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.database.entities.ServerEntity
import java.util.UUID

@Dao
interface ServerDao {
    @Upsert
    suspend fun upsertServer(server: ServerEntity)

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServer(id: UUID): ServerEntity?

    @Upsert
    suspend fun upsertAddresses(addresses: List<ServerAddressEntity>)

    @Query("SELECT * FROM server_addresses WHERE serverId = :serverId")
    suspend fun getAddresses(serverId: UUID): List<ServerAddressEntity>

    /** Its address and user rows go with it, through the `CASCADE` foreign keys. */
    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: UUID)
}
