package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A server may be reachable via several addresses (local network, remote/tunnel, manually added candidates)
 * that `ServerReachabilityProbe` rotates through. Its own table rather than a column on [ServerEntity], which
 * keeps the schema ready for multi-server support without a migration. `CASCADE`s with its server.
 */
@Entity(
    tableName = "server_addresses",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["serverId", "address"], unique = true),
    ],
)
data class ServerAddressEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverId: UUID,
    val address: String,
)
