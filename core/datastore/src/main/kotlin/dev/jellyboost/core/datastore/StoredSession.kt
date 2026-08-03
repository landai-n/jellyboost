package dev.jellyboost.core.datastore

import java.util.UUID

/**
 * A signed-in Jellyfin session, as persisted by [SecureCredentialStore].
 *
 * [serverId] and [userId] are stored alongside [accessToken] so that restoring a session on
 * app start is a single atomic read, and signing out is a single atomic wipe — there is no
 * window where the token and the identifiers it belongs to can disagree.
 *
 * The [accessToken] must NEVER be persisted anywhere other than [SecureCredentialStore]
 * (not Room, not `DataStore`, not logs) — see `docs/PLAN.md`, `:core:datastore` row.
 */
data class StoredSession(
    val serverId: UUID,
    val userId: UUID,
    val accessToken: String,
) {
    /**
     * Redacts [accessToken] (audit NET-02, same shape as SEC-09's `LoginUiState`): the generated
     * data-class `toString()` would print the live token the moment an instance reaches a log
     * line — a `Timber` call that dumps a whole value, or a wrapped exception message, is all it
     * would take.
     */
    override fun toString(): String = "StoredSession(serverId=$serverId, userId=$userId, accessToken=<redacted>)"
}
