package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * Deliberately carries no access token: it is written straight to `SecureCredentialStore` and pushed onto the
 * SDK `ApiClient`, and must never travel through UI state.
 *
 * [downloadPolicyAllowed] mirrors the server's `UserPolicy.enableContentDownloading`, confirmed at sign-in
 * because a server that disables downloading forces the offline feature onto a fallback path.
 */
data class AuthenticatedSession(
    val serverId: UUID,
    val userId: UUID,
    val userName: String,
    val serverName: String,
    val serverVersion: String?,
    val downloadPolicyAllowed: Boolean,
)
