package dev.jellyboost.core.datastore

import java.util.UUID

/**
 * [serverId] and [userId] are stored alongside [accessToken] so restoring is one atomic read and signing out
 * one atomic wipe — there is no window where the token and the identifiers can disagree.
 *
 * The [accessToken] must NEVER be persisted anywhere other than [SecureCredentialStore] — not Room, not
 * `DataStore`, not logs.
 */
data class StoredSession(
    val serverId: UUID,
    val userId: UUID,
    val accessToken: String,
) {
    /**
     * Redacts [accessToken]: the generated `toString()` would print the live token the moment an instance
     * reaches a log line or a wrapped exception message.
     */
    override fun toString(): String = "StoredSession(serverId=$serverId, userId=$userId, accessToken=<redacted>)"
}
