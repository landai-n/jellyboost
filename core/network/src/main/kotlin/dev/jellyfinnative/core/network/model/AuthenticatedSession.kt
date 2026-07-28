package dev.jellyfinnative.core.network.model

import java.util.UUID

/**
 * The outcome of a successful sign-in, as handed back to the auth feature.
 *
 * Deliberately carries no access token: the token is written straight to
 * `SecureCredentialStore` and pushed onto the SDK `ApiClient` by `AuthRepository`, and must
 * never travel through UI state (docs/PLAN.md, `:core:datastore` row).
 *
 * [downloadPolicyAllowed] mirrors the server-side `UserPolicy.enableContentDownloading` flag.
 * docs/PLAN.md risk #4 requires this to be confirmed at M1, because a server that disables
 * content downloading forces the offline feature onto a fallback path.
 */
data class AuthenticatedSession(
    val serverId: UUID,
    val userId: UUID,
    val userName: String,
    val serverName: String,
    val serverVersion: String?,
    val downloadPolicyAllowed: Boolean,
)
