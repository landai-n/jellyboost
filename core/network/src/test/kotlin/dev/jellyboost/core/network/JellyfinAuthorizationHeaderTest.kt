package dev.jellyboost.core.network

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JellyfinAuthorizationHeaderTest {
    private val apiClient =
        mockk<ApiClient>(relaxed = true) {
            every { clientInfo } returns ClientInfo(name = "Jellyboost", version = "0.1.0")
            every { deviceInfo } returns DeviceInfo(id = "device-1", name = "test tablet")
            every { accessToken } returns "token-123"
        }

    @Test
    @DisplayName("delegates to AuthorizationHeaderBuilder with the client's own session fields")
    fun buildsHeaderFromApiClient() {
        val expected =
            AuthorizationHeaderBuilder.buildHeader(
                clientName = apiClient.clientInfo.name,
                clientVersion = apiClient.clientInfo.version,
                deviceId = apiClient.deviceInfo.id,
                deviceName = apiClient.deviceInfo.name,
                accessToken = apiClient.accessToken,
            )

        jellyfinAuthorizationHeader(apiClient) shouldBe expected
    }

    @Test
    @DisplayName("rebuilds from the client on every call, so a rotated token is picked up")
    fun rebuildsPerCall() {
        every { apiClient.accessToken } returns "first"
        val first = jellyfinAuthorizationHeader(apiClient)

        every { apiClient.accessToken } returns "second"
        val second = jellyfinAuthorizationHeader(apiClient)

        second shouldBe
            AuthorizationHeaderBuilder.buildHeader(
                clientName = apiClient.clientInfo.name,
                clientVersion = apiClient.clientInfo.version,
                deviceId = apiClient.deviceInfo.id,
                deviceName = apiClient.deviceInfo.name,
                accessToken = "second",
            )
        (first == second) shouldBe false
    }

    @Test
    @DisplayName("same scheme, host and port is the same origin")
    fun sameOrigin() {
        val a = "https://server.example:8096/path".toHttpUrl()
        val b = "https://server.example:8096/other".toHttpUrl()

        a.isSameOrigin(b) shouldBe true
    }

    @Test
    @DisplayName("a different scheme is a different origin, even on the same host and port")
    fun differentScheme() {
        val https = "https://server.example:8096/".toHttpUrl()
        val http = "http://server.example:8096/".toHttpUrl()

        https.isSameOrigin(http) shouldBe false
    }

    @Test
    @DisplayName("a different host is a different origin")
    fun differentHost() {
        val server = "https://server.example/".toHttpUrl()
        val other = "https://evil.example/".toHttpUrl()

        server.isSameOrigin(other) shouldBe false
    }

    @Test
    @DisplayName("a different effective port is a different origin")
    fun differentPort() {
        // No explicit port on either side: HttpUrl fills in the scheme default (443), so this is the same
        // origin as an explicit ":443".
        val implicitPort = "https://server.example/".toHttpUrl()
        val explicitDefaultPort = "https://server.example:443/".toHttpUrl()
        val otherPort = "https://server.example:8443/".toHttpUrl()

        implicitPort.isSameOrigin(explicitDefaultPort) shouldBe true
        implicitPort.isSameOrigin(otherPort) shouldBe false
    }
}
