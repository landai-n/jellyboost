package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * The outcome of a successful sign-in, as handed back to the auth feature.
 *
 * Deliberately carries no access token: the token is written straight to
 * `SecureCredentialStore` and pushed onto the SDK `ApiClient` by `AuthRepository`, and must
 * never travel through UI state.
 *
 * [downloadPolicyAllowed] mirrors the server-side `UserPolicy.enableContentDownloading` flag.
 * This is confirmed at sign-in, because a server that disables content downloading forces the
 * offline feature onto a fallback path.
 */
data class AuthenticatedSession(
    val serverId: UUID,
    val userId: UUID,
    val userName: String,
    val serverName: String,
    val serverVersion: String?,
    val downloadPolicyAllowed: Boolean,
)
