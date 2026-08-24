package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * Starts at [Unknown] so the app can hold the splash screen until `restoreSession()` has answered, without
 * flashing the login screen at a user who is in fact signed in.
 */
sealed interface SessionState {
    data object Unknown : SessionState

    data object LoggedOut : SessionState

    /** Everything here comes from Room + `SecureCredentialStore`, so this state is reachable with no network. */
    data class LoggedIn(
        val serverId: UUID,
        val userId: UUID,
        val userName: String,
        val serverName: String,
        val serverVersion: String?,
    ) : SessionState
}
