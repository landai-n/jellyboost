package dev.jellyboost.core.network

import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BrandingOptionsDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto

/**
 * The SDK exposes its operations as extension properties on `ApiClient`, which cannot be stubbed; routing
 * every call through this seam is what makes the repositories unit-testable. Keep implementations dumb — no
 * error mapping, no branching, no domain types — so the untested surface stays mechanical.
 */
internal interface JellyfinApiFacade {
    suspend fun getAddressCandidates(input: String): List<String>

    suspend fun getRecommendedServers(candidates: Collection<String>): List<RecommendedServerInfo>

    fun discoverLocalServers(): Flow<ServerDiscoveryInfo>

    suspend fun getPublicUsers(): List<UserDto>

    suspend fun getBrandingOptions(): BrandingOptionsDto

    suspend fun getQuickConnectEnabled(): Boolean

    suspend fun authenticateUserByName(
        username: String,
        password: String,
    ): AuthenticationResult

    suspend fun initiateQuickConnect(): QuickConnectResult

    suspend fun getQuickConnectState(secret: String): QuickConnectResult

    suspend fun authenticateWithQuickConnect(secret: String): AuthenticationResult

    suspend fun reportSessionEnded()
}
