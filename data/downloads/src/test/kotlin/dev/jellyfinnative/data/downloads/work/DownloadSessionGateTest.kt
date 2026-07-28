package dev.jellyfinnative.data.downloads.work

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
 * Unit tests for [DownloadSessionGate] — the fix for the cold-start race the M7 device walk found.
 *
 * The property under test is narrow but load-bearing: a download worker that starts before the app
 * has restored its session must restore it itself, and must never turn "the process was cold" into
 * a failed download.
 */
class DownloadSessionGateTest {
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
            // What the worker sees on a relaunch after `am force-stop`: WorkManager is running
            // before `MainViewModel` has restored anything.
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
            // `FileDownloader` builds an Authorization header from the token; half a session would
            // fail later, as a 401 on the media file.
            every { apiClient.baseUrl } returns "https://server"
            every { apiClient.accessToken } returns null
            coEvery { sessionRepository.restoreSession() } returns Unit

            gate().ensureSession() shouldBe false
        }

    private fun gate() = DownloadSessionGate(sessionRepository = sessionRepository, apiClient = apiClient)

    private fun configured() {
        every { apiClient.baseUrl } returns "https://server"
        every { apiClient.accessToken } returns "token"
    }

    private fun unconfigured() {
        every { apiClient.baseUrl } returns null
        every { apiClient.accessToken } returns null
    }
}
