package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * Whether this device currently has a usable Jellyfin session.
 *
 * Starts at [Unknown] so the app can hold the splash screen until
 * `SessionRepository.restoreSession()` has answered, without flashing the login screen at a
 * user who is in fact signed in.
 */
sealed interface SessionState {
    /** Session restore has not finished yet. */
    data object Unknown : SessionState

    /** No stored session, or the user signed out. */
    data object LoggedOut : SessionState

    /**
     * A session is active. Everything here comes from Room + `SecureCredentialStore`, so this
     * state is reachable with no network at all.
     */
    data class LoggedIn(
        val serverId: UUID,
        val userId: UUID,
        val userName: String,
        val serverName: String,
        val serverVersion: String?,
    ) : SessionState
}
