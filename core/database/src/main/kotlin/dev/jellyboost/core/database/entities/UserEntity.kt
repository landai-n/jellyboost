package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * IMPORTANT: this entity intentionally has NO access-token column. Tokens live only in
 * `SecureCredentialStore`, never in Room — verified via `run-as` inspection of the database file. Session
 * restore pairs a row here with the token looked up separately. `CASCADE`s with its [ServerEntity].
 */
@Entity(
    tableName = "users",
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
    ],
)
data class UserEntity(
    @PrimaryKey
    val id: UUID,
    val serverId: UUID,
    val name: String,
    val primaryImageTag: String?,
)
