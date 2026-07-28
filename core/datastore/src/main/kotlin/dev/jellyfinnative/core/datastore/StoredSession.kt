package dev.jellyfinnative.core.datastore

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
)
