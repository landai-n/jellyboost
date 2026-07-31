package dev.jellyboost.core.network

import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BrandingOptionsDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto

/**
 * One method per Jellyfin SDK call the auth/session layer makes, and nothing else.
 *
 * The SDK exposes its operations as extension properties on `ApiClient`
 * (`apiClient.userApi`, `apiClient.brandingApi`, …), which cannot be stubbed. Routing every
 * call through this seam is what makes the repositories unit-testable. Keep implementations
 * dumb — no error mapping, no branching, no domain types — so that the untested surface stays
 * mechanical.
 */
internal interface JellyfinApiFacade {
    /** Expands user input ("myserver", "10.0.0.5:8096", …) into candidate base URLs. */
    suspend fun getAddressCandidates(input: String): List<String>

    /** Probes [candidates] and scores each one. */
    suspend fun getRecommendedServers(candidates: Collection<String>): List<RecommendedServerInfo>

    /** Servers announcing themselves on the local network (UDP broadcast). */
    fun discoverLocalServers(): Flow<ServerDiscoveryInfo>

    /** Users the current server advertises on its login screen. */
    suspend fun getPublicUsers(): List<UserDto>

    /** Server branding, of which only the login disclaimer is used. */
    suspend fun getBrandingOptions(): BrandingOptionsDto

    /** Whether the current server has Quick Connect turned on. */
    suspend fun getQuickConnectEnabled(): Boolean

    /** Exchanges username + password for an access token. */
    suspend fun authenticateUserByName(
        username: String,
        password: String,
    ): AuthenticationResult

    /** Opens a Quick Connect request and returns its secret + user-facing code. */
    suspend fun initiateQuickConnect(): QuickConnectResult

    /** Current status of the Quick Connect request identified by [secret]. */
    suspend fun getQuickConnectState(secret: String): QuickConnectResult

    /** Exchanges an approved Quick Connect [secret] for an access token. */
    suspend fun authenticateWithQuickConnect(secret: String): AuthenticationResult

    /** Tells the server this device's session has ended (best effort, on sign-out). */
    suspend fun reportSessionEnded()
}
