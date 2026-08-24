package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import java.time.Instant
import java.util.UUID

/**
 * Per-user playback state for one item, held locally so that every user-data write is
 * **local-first**: the row is updated (and the UI patched) before the server is told anything.
 *
 * The composite primary key `(itemId, userId)` is what makes the table multi-user ready without a
 * separate scoping mechanism, even though multi-server/multi-user is not yet exposed in the UI.
 *
 * [toBeSynced] is the whole point of the table: it marks a row the server has not accepted yet.
 * `UserDataSyncWorker` drains those rows (most-recent-wins), and the download-delete cascade
 * keeps a `UserDataEntity` only while this flag is set.
 *
 * Like every other entity in this module, this one carries NO access token — tokens live only in
 * `:core:datastore`'s `SecureCredentialStore`.
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
    /** When the server (or this device) last played the item; `null` if never played. */
    val lastPlayedDate: Instant? = null,
    /** `true` while this row holds a local change the server has not accepted yet. */
    val toBeSynced: Boolean = false,
    /** When this row was last written locally — the local half of most-recent-wins. */
    val updatedAt: Instant,
)
