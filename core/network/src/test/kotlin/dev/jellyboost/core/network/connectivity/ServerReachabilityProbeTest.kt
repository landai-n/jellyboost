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
import java.util.UUID

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

    /** Built per test so the probe's IO dispatcher shares `runTest`'s scheduler, or the budget is real time. */
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
            coVerify(exactly = 0) { probeApi.reachableServerId(any()) }
        }

    @Test
    fun `tries the address the client is already using first`() =
        runTest {
            coEvery { probeApi.reachableServerId(LAN) } returns SERVER_ID

            probe().isServerReachable() shouldBe true

            coVerify(exactly = 0) { probeApi.reachableServerId(REMOTE) }
            verify(exactly = 0) { apiClientProvider.useAddress(any()) }
        }

    @Test
    fun `rotates to the next stored address when the current one is silent`() =
        runTest {
            coEvery { probeApi.reachableServerId(LAN) } returns null
            coEvery { probeApi.reachableServerId(REMOTE) } returns SERVER_ID

            probe().isServerReachable() shouldBe true

            verify(exactly = 1) { apiClientProvider.useAddress(REMOTE) }
        }

    @Test
    fun `never switches to an address that answers as a different server`() =
        runTest {
            // The attack this guards: the user's home LAN address answered, on some other network, by a host
            // that is not their server. It must not receive the client — and its token.
            coEvery { probeApi.reachableServerId(LAN) } returns IMPOSTOR_ID
            coEvery { probeApi.reachableServerId(REMOTE) } returns SERVER_ID

            probe().isServerReachable() shouldBe true

            verify(exactly = 0) { apiClientProvider.useAddress(LAN) }
            verify(exactly = 1) { apiClientProvider.useAddress(REMOTE) }
        }

    @Test
    fun `reports unreachable when the only answering host is not our server`() =
        runTest {
            coEvery { probeApi.reachableServerId(LAN) } returns IMPOSTOR_ID
            coEvery { probeApi.reachableServerId(REMOTE) } returns null

            probe().isServerReachable() shouldBe false

            verify(exactly = 0) { apiClientProvider.useAddress(any()) }
        }

    @Test
    fun `reports unreachable only after every candidate has been tried`() =
        runTest {
            coEvery { probeApi.reachableServerId(any()) } returns null

            probe().isServerReachable() shouldBe false

            coVerify(exactly = 1) { probeApi.reachableServerId(LAN) }
            coVerify(exactly = 1) { probeApi.reachableServerId(REMOTE) }
            verify(exactly = 0) { apiClientProvider.useAddress(any()) }
        }

    @Test
    fun `probes each address at most once even when it is stored twice`() =
        runTest {
            // `getAddresses` also returns the address the client already uses, so a naive list probes it twice.
            coEvery { probeApi.reachableServerId(any()) } returns null

            probe().isServerReachable() shouldBe false

            coVerify(exactly = 1) { probeApi.reachableServerId(LAN) }
        }

    @Test
    fun `gives each address only the probe budget before moving on`() =
        runTest {
            coEvery { probeApi.reachableServerId(LAN) } coAnswers {
                delay(HANGING_FOREVER_MS)
                SERVER_ID
            }
            coEvery { probeApi.reachableServerId(REMOTE) } returns SERVER_ID

            val start = testScheduler.currentTime
            probe().isServerReachable() shouldBe true

            // Exactly one budget spent on the hanging address, not a 30-second socket timeout.
            (testScheduler.currentTime - start) shouldBe ServerReachabilityProbe.PROBE_TIMEOUT_MS
        }

    @Test
    fun `a database failure still lets the current address be probed`() =
        runTest {
            coEvery { serverDao.getAddresses(any()) } throws IOException("database gone")
            coEvery { probeApi.reachableServerId(LAN) } returns SERVER_ID

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

        /** A host that answers the probe but is not the server the user signed in to. */
        val IMPOSTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000bad1")

        /** Far longer than any budget, but finite so the virtual clock stays sane. */
        const val HANGING_FOREVER_MS = 10 * 60 * 1000L
    }
}
