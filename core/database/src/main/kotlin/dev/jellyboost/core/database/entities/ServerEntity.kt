package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A Jellyfin server the user has connected to.
 *
 * [version] is the server's reported version string, captured at login
 * (`getPublicSystemInfo`/`getSystemInfo`) and refreshed on every reconnect (docs/PLAN.md, M1:
 * "confirm server version"). It is informational only — no access token is stored on this
 * entity or anywhere in this database; tokens live exclusively in
 * `:core:datastore`'s `SecureCredentialStore` (EncryptedSharedPreferences).
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey
    val id: UUID,
    val name: String,
    val version: String?,
)
