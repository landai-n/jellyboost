package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.network.ApiClientProvider
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.TestFixtures.SERVER_ID
import dev.jellyboost.core.network.TestFixtures.SERVER_NAME
import dev.jellyboost.core.network.TestFixtures.SERVER_VERSION
import dev.jellyboost.core.network.TestFixtures.USER_ID
import dev.jellyboost.core.network.TestFixtures.USER_NAME
import dev.jellyboost.core.network.model.SessionState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Unit tests for [ServerReachabilityProbe] — candidate rotation and the per-address time budget.
 *
 * The rotation is the reason a user who walks out of the house does not get an offline app: the
 * LAN address stops answering and the remote address takes over without them noticing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityProbeTest {
    private val probeApi = mockk<ServerProbeApi>()
    private val serverDao = mockk<ServerDao>()
    private val sessionStateHolder = SessionStateHolder()
    private val apiClient = mockk<ApiClient>(relaxed = true)
    private val apiClientProvider = mockk<ApiClientProvider>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { apiClientProvider.apiClient } returns apiClient
        every { apiClient.baseUrl } returns LAN
        coEvery { serverDao.getAddresses(SERVER_ID) } returns listOf(address(LAN), address(REMOTE))
        sessionStateHolder.update(loggedIn())
    }

    /**
     * Built per test so the probe's IO dispatcher shares `runTest`'s scheduler — without that,
     * `withTimeoutOrNull` would run on real time and the budget test could not be written at all.
     */
    private fun TestScope.probe() =
        ServerReachabilityProbe(
            probeApi = probeApi,
            serverDao = serverDao,
            sessionStateHolder = sessionStateHolder,
            apiClientProvider = apiClientProvider,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    fun `reports unreachable when nobody is signed in`() =
        runTest {
            sessionStateHolder.update(SessionState.LoggedOut)

            probe().isServerReachable() shouldBe false
            // Not "we tried and failed" — there is simply no server to probe.
            coVerify(exactly = 0) { probeApi.isReachable(any()) }
        }

    @Test
    fun `tries the address the client is already using first`() =
        runTest {
            coEvery { probeApi.isReachable(LAN) } returns true

            probe().isServerReachable() shouldBe true

            coVerify(exactly = 0) { probeApi.isReachable(REMOTE) }
            // Already pointed there: no reason to touch the client.
            verify(exactly = 0) { apiClientProvider.useAddress(any()) }
        }

    @Test
    fun `rotates to the next stored address when the current one is silent`() =
        runTest {
            coEvery { probeApi.isReachable(LAN) } returns false
            coEvery { probeApi.isReachable(REMOTE) } returns true

            probe().isServerReachable() shouldBe true

            verify(exactly = 1) { apiClientProvider.useAddress(REMOTE) }
        }

    @Test
    fun `reports unreachable only after every candidate has been tried`() =
        runTest {
            coEvery { probeApi.isReachable(any()) } returns false

            probe().isServerReachable() shouldBe false

            coVerify(exactly = 1) { probeApi.isReachable(LAN) }
            coVerify(exactly = 1) { probeApi.isReachable(REMOTE) }
            verify(exactly = 0) { apiClientProvider.useAddress(any()) }
        }

    @Test
    fun `probes each address at most once even when it is stored twice`() =
        runTest {
            // `getAddresses` also returns the address the client already uses, so the naive
            // "current + stored" list would probe it twice.
            coEvery { probeApi.isReachable(any()) } returns false

            probe().isServerReachable() shouldBe false

            coVerify(exactly = 1) { probeApi.isReachable(LAN) }
        }

    @Test
    fun `gives each address only the probe budget before moving on`() =
        runTest {
            // A silent server: the socket never answers, it just hangs.
            coEvery { probeApi.isReachable(LAN) } coAnswers {
                delay(HANGING_FOREVER_MS)
                true
            }
            coEvery { probeApi.isReachable(REMOTE) } returns true

            val start = testScheduler.currentTime
            probe().isServerReachable() shouldBe true

            // Exactly one budget was spent on the hanging address, not a 30-second socket timeout.
            (testScheduler.currentTime - start) shouldBe ServerReachabilityProbe.PROBE_TIMEOUT_MS
        }

    @Test
    fun `a database failure still lets the current address be probed`() =
        runTest {
            coEvery { serverDao.getAddresses(any()) } throws IOException("database gone")
            coEvery { probeApi.isReachable(LAN) } returns true

            probe().isServerReachable() shouldBe true
        }

    private fun address(value: String) = ServerAddressEntity(serverId = SERVER_ID, address = value)

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = SERVER_ID,
            userId = USER_ID,
            userName = USER_NAME,
            serverName = SERVER_NAME,
            serverVersion = SERVER_VERSION,
        )

    private companion object {
        const val LAN = "http://192.168.1.10:8096"
        const val REMOTE = "https://media.example.com"

        /** Far longer than any budget, but finite so the virtual clock stays sane. */
        const val HANGING_FOREVER_MS = 10 * 60 * 1000L
    }
}
