package dev.jellyboost.core.network

import dev.jellyboost.core.network.model.ResolvedServer
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import java.time.LocalDateTime
import java.util.UUID

internal object TestFixtures {
    val SERVER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val USER_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    const val SERVER_ADDRESS = "https://media.example.com"
    const val SERVER_NAME = "Living Room"
    const val SERVER_VERSION = "10.11.0"
    const val USER_NAME = "casey"
    const val ACCESS_TOKEN = "super-secret-token-value"

    val resolvedServer =
        ResolvedServer(
            serverId = SERVER_ID,
            name = SERVER_NAME,
            version = SERVER_VERSION,
            address = SERVER_ADDRESS,
        )

    /** [systemInfo] failing marks the address as unreachable. */
    fun recommendedServer(
        address: String,
        score: RecommendedServerInfoScore,
        systemInfo: Result<PublicSystemInfo> = Result.success(publicSystemInfo()),
    ): RecommendedServerInfo =
        RecommendedServerInfo(
            address = address,
            responseTime = 1L,
            score = score,
            issues = emptyList(),
            systemInfo = systemInfo,
        )

    fun publicSystemInfo(
        id: String = SERVER_ID.toString(),
        serverName: String = SERVER_NAME,
        version: String = SERVER_VERSION,
    ): PublicSystemInfo =
        PublicSystemInfo(
            id = id,
            serverName = serverName,
            version = version,
        )

    fun userDto(
        id: UUID = USER_ID,
        name: String = USER_NAME,
        primaryImageTag: String? = "tag",
        downloadPolicyAllowed: Boolean = true,
    ): UserDto =
        UserDto(
            id = id,
            name = name,
            primaryImageTag = primaryImageTag,
            hasPassword = true,
            hasConfiguredPassword = true,
            hasConfiguredEasyPassword = false,
            policy = userPolicy(downloadPolicyAllowed),
        )

    fun authenticationResult(
        user: UserDto = userDto(),
        accessToken: String? = ACCESS_TOKEN,
    ): AuthenticationResult =
        AuthenticationResult(
            user = user,
            sessionInfo = null,
            accessToken = accessToken,
            serverId = SERVER_ID.toString(),
        )

    fun quickConnectResult(
        authenticated: Boolean,
        secret: String = "quick-connect-secret",
        code: String = "123456",
    ): QuickConnectResult =
        QuickConnectResult(
            authenticated = authenticated,
            secret = secret,
            code = code,
            deviceId = "device",
            deviceName = "test tablet",
            appName = "Jellyboost",
            appVersion = "0.1.0",
            dateAdded = LocalDateTime.of(2026, 7, 28, 12, 0),
        )

    /** [UserPolicy] has ~44 constructor parameters, of which exactly one matters here, so it is stubbed. */
    private fun userPolicy(downloadPolicyAllowed: Boolean): UserPolicy =
        mockk<UserPolicy> {
            every { enableContentDownloading } returns downloadPolicyAllowed
        }
}
