package dev.jellyboost.core.network.connectivity

import app.cash.turbine.test
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.ConnectionState
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
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateProviderTest {
    private val hasNetworkFlow = MutableStateFlow(true)
    private val forceOfflineFlow = MutableStateFlow(false)
    private val probe = mockk<ServerReachabilityProbe>()

    private val sessionStateHolder = SessionStateHolder()

    private val monitor =
        object : ConnectivityMonitor {
            override val hasNetwork: Flow<Boolean> = hasNetworkFlow
        }

    private val preferences =
        object : AppPreferences {
            override val forceOffline: Flow<Boolean> = forceOfflineFlow

            override suspend fun setForceOffline(enabled: Boolean) {
                forceOfflineFlow.value = enabled
            }

            override val downloadOverWifiOnly: Flow<Boolean> = MutableStateFlow(true)

            override suspend fun setDownloadOverWifiOnly(enabled: Boolean) = Unit

            override val downloadQuality: Flow<DownloadQuality> = MutableStateFlow(DownloadQuality.ORIGINAL)

            override suspend fun setDownloadQuality(quality: DownloadQuality) = Unit

            override val downloadStorageVolumeId: Flow<String?> = MutableStateFlow(null)

            override suspend fun setDownloadStorageVolumeId(volumeId: String?) = Unit

            override val introSkipMode: Flow<SegmentSkipMode> = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)

            override suspend fun setIntroSkipMode(mode: SegmentSkipMode) = Unit

            override val outroSkipMode: Flow<SegmentSkipMode> = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)

            override suspend fun setOutroSkipMode(mode: SegmentSkipMode) = Unit

            override val pipOnLeave: Flow<Boolean> = MutableStateFlow(true)

            override suspend fun setPipOnLeave(enabled: Boolean) = Unit

            override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

            override suspend fun setThemeMode(mode: ThemeMode) = Unit

            override val dynamicColorEnabled: Flow<Boolean> = MutableStateFlow(false)

            override suspend fun setDynamicColorEnabled(enabled: Boolean) = Unit

            override val styledAssSubtitles: Flow<Boolean> = MutableStateFlow(false)

            override suspend fun setStyledAssSubtitles(enabled: Boolean) = Unit

            override val maxStreamingBitrate: Flow<Int?> = MutableStateFlow(null)

            override suspend fun setMaxStreamingBitrate(bitrate: Int?) = Unit
        }

    private var applicationScope: CoroutineScope? = null

    @BeforeEach
    fun setUp() {
        coEvery { probe.isServerReachable() } returns true
    }

    @AfterEach
    fun tearDown() {
        // The provider's collectors never complete by design; the app scope dies with the process.
        applicationScope?.cancel()
    }

    /**
     * The unreachable re-probe is an endless timer by design, and `runTest` finishes by running the scheduler
     * until it is idle — which never arrives with that timer armed, so the scope must be cancelled first.
     */
    private fun connectivityTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                applicationScope?.cancel()
            }
        }

    /** The scope shares `runTest`'s scheduler but must not be a child of the test coroutine, or it never finishes. */
    private fun TestScope.newProvider(): ConnectionStateProvider {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        applicationScope = scope
        return ConnectionStateProvider(
            connectivityMonitor = monitor,
            sessionStateHolder = sessionStateHolder,
            probe = probe,
            appPreferences = preferences,
            scope = scope,
        )
    }

    private fun TestScope.newSignedInProvider(): ConnectionStateProvider {
        val provider = newProvider()
        settle()
        sessionStateHolder.update(loggedIn())
        settle()
        return provider
    }

    /**
     * `advanceUntilIdle` cannot be used once the state may settle on [ConnectionState.OFFLINE_SERVER_UNREACHABLE]:
     * the re-probe loop is an endless timer, so "until the scheduler runs dry" never arrives and the test hangs.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    private fun TestScope.advancePastReprobe() {
        advanceTimeBy(ConnectionStateProvider.UNREACHABLE_REPROBE_MS)
        settle()
    }

    private fun loggedIn(serverVersion: String? = SERVER_VERSION) =
        SessionState.LoggedIn(
            serverId = SERVER_ID,
            userId = USER_ID,
            userName = USER_NAME,
            serverName = SERVER_NAME,
            serverVersion = serverVersion,
        )

    @Test
    fun `is online when the network is up and the server answers`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `reports no network the moment the monitor says so`() =
        connectivityTest {
            val provider = newSignedInProvider()

            hasNetworkFlow.value = false
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_NO_NETWORK
        }

    @Test
    fun `reports the server unreachable when the network is up but the probe fails`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false

            val provider = newSignedInProvider()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
        }

    @Test
    fun `forced offline outranks a perfectly good network`() =
        connectivityTest {
            val provider = newSignedInProvider()

            forceOfflineFlow.value = true
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_FORCED
        }

    @Test
    fun `forced offline outranks having no network at all`() =
        connectivityTest {
            val provider = newProvider()
            hasNetworkFlow.value = false
            forceOfflineFlow.value = true

            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_FORCED
        }

    @Test
    fun `no network outranks an unreachable server`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            hasNetworkFlow.value = false
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_NO_NETWORK
        }

    @Test
    fun `turning forced offline back off restores the observed state`() =
        connectivityTest {
            val provider = newSignedInProvider()
            forceOfflineFlow.value = true
            settle()

            forceOfflineFlow.value = false
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `probes once the restored session gives it a server to probe`() =
        connectivityTest {
            newSignedInProvider()

            coVerify(exactly = 1) { probe.isServerReachable() }
        }

    @Test
    fun `probes again when the network comes back`() =
        connectivityTest {
            newSignedInProvider()

            hasNetworkFlow.value = false
            settle()
            hasNetworkFlow.value = true
            settle()

            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    @Test
    fun `collapses a burst of reported failures into a single probe`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.reportFailure()
            runCurrent()
            repeat(SCREENFUL_OF_REQUESTS) { provider.reportFailure() }
            settle()

            // Restored-session probe + the first failure + one for the whole burst — not ten.
            coVerify(exactly = 3) { probe.isServerReachable() }
        }

    @Test
    fun `holds off a follow-up probe until the debounce window has passed`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.reportFailure()
            runCurrent()
            provider.reportFailure()
            advanceTimeBy(ConnectionStateProvider.PROBE_DEBOUNCE_MS / 2)
            runCurrent()

            coVerify(exactly = 2) { probe.isServerReachable() }

            settle()

            coVerify(exactly = 3) { probe.isServerReachable() }
        }

    @Test
    fun `recovers to online when a refresh finds the server again`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            coEvery { probe.isServerReachable() } returns true
            provider.refresh()
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `re-probes when a session appears, with no connectivity change at all`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()
            settle()
            sessionStateHolder.update(SessionState.LoggedOut)
            settle()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            coEvery { probe.isServerReachable() } returns true
            sessionStateHolder.update(loggedIn())
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    @Test
    fun `keeps the launch optimism while the session is still unknown`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false

            val provider = newProvider()
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
            coVerify(exactly = 0) { probe.isServerReachable() }
        }

    @Test
    fun `probes as soon as the restore publishes a session, and applies the verdict`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()
            settle()

            sessionStateHolder.update(loggedIn())
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
            coVerify(exactly = 1) { probe.isServerReachable() }
        }

    @Test
    fun `re-probes on sign-out so no stale verdict outlives the session`() =
        connectivityTest {
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.ONLINE

            coEvery { probe.isServerReachable() } returns false
            sessionStateHolder.update(SessionState.LoggedOut)
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    @Test
    fun `does not probe again when the same session is republished`() =
        connectivityTest {
            newSignedInProvider()

            sessionStateHolder.update(loggedIn(serverVersion = "10.11.1"))
            settle()

            coVerify(exactly = 1) { probe.isServerReachable() }
        }

    @Test
    fun `probes once per session identity, not once per emission`() =
        connectivityTest {
            newProvider()
            settle()

            repeat(SCREENFUL_OF_REQUESTS) { sessionStateHolder.update(loggedIn(serverVersion = "10.11.$it")) }
            settle()

            coVerify(exactly = 1) { probe.isServerReachable() }
        }

    @Test
    fun `survives a probe that throws and keeps answering later ones`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
            val provider = newSignedInProvider()

            coEvery { probe.isServerReachable() } returns false
            provider.refresh()
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    @Test
    fun `keeps the last verdict when a probe throws`() =
        connectivityTest {
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.ONLINE

            coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
            provider.refresh()
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `leaves the state flow alive and collectable after a throwing probe`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
            val provider = newSignedInProvider()

            provider.state.test {
                awaitItem() shouldBe ConnectionState.ONLINE

                hasNetworkFlow.value = false
                settle()

                awaitItem() shouldBe ConnectionState.OFFLINE_NO_NETWORK
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ticks a reconfirmation when a probe confirms the server after a reported failure`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.serverReconfirmed.test {
                runCurrent()

                provider.reportFailure()
                settle()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `stays quiet for a probe nobody reported a failure for`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.serverReconfirmed.test {
                runCurrent()

                provider.refresh()
                settle()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stays quiet when the probe answering the failure also changes the verdict`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            provider.serverReconfirmed.test {
                runCurrent()

                coEvery { probe.isServerReachable() } returns true
                provider.reportFailure()
                settle()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `keeps a reported failure pending when the probe throws`() =
        connectivityTest {
            val provider = newSignedInProvider()

            provider.serverReconfirmed.test {
                runCurrent()

                coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
                provider.reportFailure()
                settle()
                expectNoEvents()

                coEvery { probe.isServerReachable() } returns true
                provider.refresh()
                settle()

                awaitItem() shouldBe Unit
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `comes back online by itself when a later re-probe finds the server`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            coEvery { probe.isServerReachable() } returns true
            advancePastReprobe()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `keeps re-probing when the re-probe fails as well`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()

            advancePastReprobe()

            coVerify(exactly = 2) { probe.isServerReachable() }

            // A failed re-probe writes `false` over `false` and moves no state — the ticking has to survive that.
            advancePastReprobe()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
            coVerify(exactly = 3) { probe.isServerReachable() }
        }

    private companion object {
        /** Roughly the number of parallel requests a home screen fires. */
        const val SCREENFUL_OF_REQUESTS = 8

        /** Two debounce windows: enough for a couple of chained probes, far short of a re-probe. */
        const val SETTLE_MS = ConnectionStateProvider.PROBE_DEBOUNCE_MS * 2
    }
}
