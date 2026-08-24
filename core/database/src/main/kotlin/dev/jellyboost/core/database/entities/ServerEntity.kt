package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * [version] is informational only. No access token is stored on this entity or anywhere in this database;
 * tokens live exclusively in `SecureCredentialStore`.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    val id: UUID,
    val name: String,
    val version: String?,
)
