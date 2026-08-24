package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One reachable URL for a [ServerEntity].
 *
 * A server may be reachable via several addresses (local network, remote/tunnel, manually
 * added candidates) discovered during setup or rotated through by `ServerReachabilityProbe`.
 * Modeling this as its own table — rather than a single column on [ServerEntity] — keeps the
 * schema ready for multi-server support later without a migration.
 *
 * Rows are deleted automatically (`CASCADE`) when their owning [ServerEntity] is removed.
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
