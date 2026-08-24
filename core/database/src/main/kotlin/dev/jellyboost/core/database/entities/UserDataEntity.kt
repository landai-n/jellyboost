package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import java.time.Instant
import java.util.UUID

/**
 * Per-user playback state for one item, held locally so every user-data write is **local-first**: the row is
 * updated (and the UI patched) before the server is told anything.
 *
 * [toBeSynced] marks a row the server has not accepted yet. `UserDataSyncWorker` drains those rows
 * (most-recent-wins), and the download-delete cascade keeps a `UserDataEntity` only while the flag is set.
 *
 * Carries NO access token — tokens live only in `SecureCredentialStore`.
 */
@Entity(
    tableName = "user_data",
    primaryKeys = ["itemId", "userId"],
    indices = [
        Index(value = ["toBeSynced"]),
    ],
)
data class UserDataEntity(
    val itemId: UUID,
    val userId: UUID,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val playbackPositionTicks: Long = 0L,
    val lastPlayedDate: Instant? = null,
    val toBeSynced: Boolean = false,
    /** The local half of most-recent-wins. */
    val updatedAt: Instant,
)
