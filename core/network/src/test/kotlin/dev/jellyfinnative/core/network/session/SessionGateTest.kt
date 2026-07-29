package dev.jellyfinnative.core.network.session

import dev.jellyfinnative.core.network.SessionRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SessionGate] — the fix for the cold-start race the M7 device walk found (and M8
 * hit again in the user-data sync drain).
 *
 * The property under test is narrow but load-bearing: a worker that starts before the app has
 * restored its session must restore it itself, and must never turn "the process was cold" into a
 * failure of the work it was asked to do.
 */
class SessionGateTest {
    private val sessionRepository = mockk<SessionRepository>()
    private val apiClient = mockk<ApiClient>()

    @Test
    fun `an already-configured client is not restored again`() =
        runTest {
            configured()

            gate().ensureSession() shouldBe true

            coVerify(exactly = 0) { sessionRepository.restoreSession() }
        }

    @Test
    fun `a cold start restores the stored session`() =
        runTest {
            // What a worker sees on a relaunch after `am force-stop`: WorkManager is running before
            // `MainViewModel` has restored anything.
            unconfigured()
            coEvery { sessionRepository.restoreSession() } answers { configured() }

            gate().ensureSession() shouldBe true

            coVerify(exactly = 1) { sessionRepository.restoreSession() }
        }

    @Test
    fun `a restore that yields nothing reports no session rather than pretending`() =
        runTest {
            unconfigured()
            coEvery { sessionRepository.restoreSession() } returns Unit

            gate().ensureSession() shouldBe false
        }

    @Test
    fun `a base URL without a token is not a usable session`() =
        runTest {
            // An `Authorization` header is built from the token; half a session would fail later,
            // as a 401 on the first authenticated call.
            every { apiClient.baseUrl } returns "https://server"
            every { apiClient.accessToken } returns null
            coEvery { sessionRepository.restoreSession() } returns Unit

            gate().ensureSession() shouldBe false
        }

    private fun gate() = SessionGate(sessionRepository = sessionRepository, apiClient = apiClient)

    private fun configured() {
        every { apiClient.baseUrl } returns "https://server"
        every { apiClient.accessToken } returns "token"
    }

    private fun unconfigured() {
        every { apiClient.baseUrl } returns null
        every { apiClient.accessToken } returns null
    }
}
