package dev.jellyfinnative.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyfinnative.core.database.entities.ServerAddressEntity
import dev.jellyfinnative.core.database.entities.ServerEntity
import java.util.UUID

/** Data access for [ServerEntity] and its [ServerAddressEntity] rows. */
@Dao
interface ServerDao {
    /** Inserts [server], or updates it in place if a row with the same id already exists. */
    @Upsert
    suspend fun upsertServer(server: ServerEntity)

    /** Returns the server with [id], or `null` if none is stored. */
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServer(id: UUID): ServerEntity?

    /** Inserts or updates [addresses], keyed by their (serverId, address) unique index. */
    @Upsert
    suspend fun upsertAddresses(addresses: List<ServerAddressEntity>)

    /** Returns every known address for the server with id [serverId]. */
    @Query("SELECT * FROM server_addresses WHERE serverId = :serverId")
    suspend fun getAddresses(serverId: UUID): List<ServerAddressEntity>

    /**
     * Deletes the server with [id]. Its [ServerAddressEntity] and
     * [dev.jellyfinnative.core.database.entities.UserEntity] rows are removed by the `CASCADE`
     * foreign keys.
     */
    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: UUID)
}
