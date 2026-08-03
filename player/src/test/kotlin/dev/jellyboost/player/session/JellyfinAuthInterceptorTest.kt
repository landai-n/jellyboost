package dev.jellyboost.player.session

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JellyfinAuthInterceptor]'s same-origin gate (audit NET-04).
 *
 * The header this interceptor attaches is the session's full credential, so what matters most is
 * every request it does **not** decorate: a different host, obviously, but also the same host on a
 * different port (a different service) or a different scheme (the token in cleartext). The gate
 * runs per network hop (audit NET-05), so these are exactly the redirect targets it must refuse.
 */
class JellyfinAuthInterceptorTest {
    private val apiClient = mockk<ApiClient>()
    private val interceptor = JellyfinAuthInterceptor(apiClient)

    @BeforeEach
    fun setUp() {
        every { apiClient.baseUrl } returns "https://media.example.com:8920"
        every { apiClient.accessToken } returns TOKEN
        every { apiClient.clientInfo } returns ClientInfo(name = "Jellyboost", version = "1.0")
        every { apiClient.deviceInfo } returns DeviceInfo(id = "device-id", name = "device")
    }

    /** Runs the interceptor over [url] and returns the request that went out. */
    private fun proceededRequestFor(url: String): Request {
        val request = Request.Builder().url(url).build()
        val sent = slot<Request>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(capture(sent)) } answers {
            Response
                .Builder()
                .request(sent.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        interceptor.intercept(chain)
        return sent.captured
    }

    @Test
    @DisplayName("a request to the server gets the Authorization header, token included")
    fun sameOriginGetsTheHeader() {
        val sent = proceededRequestFor("https://media.example.com:8920/Videos/abc/stream")

        sent.header("Authorization").shouldNotBeNull() shouldContain TOKEN
    }

    @Test
    @DisplayName("a default-port base URL matches a request that leaves the port implicit")
    fun defaultPortMatches() {
        every { apiClient.baseUrl } returns "https://media.example.com"

        val sent = proceededRequestFor("https://media.example.com:443/stream")

        sent.header("Authorization").shouldNotBeNull()
    }

    @Test
    @DisplayName("another host never gets the header")
    fun otherHostIsUntouched() {
        val sent = proceededRequestFor("https://cdn.example.org:8920/stream")

        sent.header("Authorization") shouldBe null
    }

    @Test
    @DisplayName("the same host on a different port is a different service and gets nothing")
    fun otherPortIsUntouched() {
        val sent = proceededRequestFor("https://media.example.com:9000/stream")

        sent.header("Authorization") shouldBe null
    }

    @Test
    @DisplayName("http on an https server would put the token on the wire in clear — refused")
    fun downgradedSchemeIsUntouched() {
        val sent = proceededRequestFor("http://media.example.com:8920/stream")

        sent.header("Authorization") shouldBe null
    }

    @Test
    @DisplayName("no base URL (signed out) means no header anywhere")
    fun noBaseUrlIsUntouched() {
        every { apiClient.baseUrl } returns null

        val sent = proceededRequestFor("https://media.example.com:8920/stream")

        sent.header("Authorization") shouldBe null
    }

    private companion object {
        const val TOKEN = "access-token-under-test"
    }
}
