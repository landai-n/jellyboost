package dev.jellyfinnative.core.network.connectivity

import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.ConnectionState
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConnectionStateProvider] — the state machine every repository call and the
 * app-wide offline banner read.
 *
 * Everything runs on `runTest`'s virtual clock, which is what makes the probe debounce assertable:
 * "one probe per burst" is a statement about time, and asserting it in wall-clock milliseconds
 * would be a flaky test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateProviderTest {
    private val hasNetworkFlow = MutableStateFlow(true)
    private val forceOfflineFlow = MutableStateFlow(false)
    private val probe = mockk<ServerReachabilityProbe>()

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

            // M7's download preference is irrelevant to connectivity; it is here only because the
            // interface every module sees now declares it.
            override val downloadOverWifiOnly: Flow<Boolean> = MutableStateFlow(true)

            override suspend fun setDownloadOverWifiOnly(enabled: Boolean) = Unit

            override val downloadQuality: Flow<DownloadQuality> = MutableStateFlow(DownloadQuality.ORIGINAL)

            override suspend fun setDownloadQuality(quality: DownloadQuality) = Unit

            // Likewise M9's player preferences: present so the fake satisfies the interface.
            override val introSkipMode: Flow<SegmentSkipMode> = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)

            override suspend fun setIntroSkipMode(mode: SegmentSkipMode) = Unit

            override val outroSkipMode: Flow<SegmentSkipMode> = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)

            override suspend fun setOutroSkipMode(mode: SegmentSkipMode) = Unit

            override val pipOnLeave: Flow<Boolean> = MutableStateFlow(true)

            override suspend fun setPipOnLeave(enabled: Boolean) = Unit
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
     * Builds the provider on a scope that shares `runTest`'s scheduler but is *not* a child of the
     * test coroutine — otherwise its endless collectors would keep `runTest` from ever finishing.
     */
    private fun TestScope.newProvider(): ConnectionStateProvider {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        applicationScope = scope
        return ConnectionStateProvider(
            connectivityMonitor = monitor,
            probe = probe,
            appPreferences = preferences,
            scope = scope,
        )
    }

    // ---- state resolution ---------------------------------------------------------------------

    @Test
    fun `is online when the network is up and the server answers`() =
        runTest {
            val provider = newProvider()

            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    @Test
    fun `reports no network the moment the monitor says so`() =
        runTest {
            val provider = newProvider()
            advanceUntilIdle()

            hasNetworkFlow.value = false
            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.OFFLINE_NO_NETWORK
        }

    @Test
    fun `reports the server unreachable when the network is up but the probe fails`() =
        runTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()

            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
        }

    @Test
    fun `forced offline outranks a perfectly good network`() =
        runTest {
            val provider = newProvider()
            advanceUntilIdle()

            forceOfflineFlow.value = true
            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.OFFLINE_FORCED
        }

    @Test
    fun `forced offline outranks having no network at all`() =
        runTest {
            val provider = newProvider()
            hasNetworkFlow.value = false
            forceOfflineFlow.value = true

            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.OFFLINE_FORCED
        }

    @Test
    fun `no network outranks an unreachable server`() =
        runTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()
            advanceUntilIdle()

            hasNetworkFlow.value = false
            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.OFFLINE_NO_NETWORK
        }

    @Test
    fun `turning forced offline back off restores the observed state`() =
        runTest {
            val provider = newProvider()
            forceOfflineFlow.value = true
            advanceUntilIdle()

            forceOfflineFlow.value = false
            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    // ---- probing ------------------------------------------------------------------------------

    @Test
    fun `probes once as soon as a network is available`() =
        runTest {
            newProvider()

            advanceUntilIdle()

            coVerify(exactly = 1) { probe.isServerReachable() }
        }

    @Test
    fun `probes again when the network comes back`() =
        runTest {
            newProvider()
            advanceUntilIdle()

            hasNetworkFlow.value = false
            advanceUntilIdle()
            hasNetworkFlow.value = true
            advanceUntilIdle()

            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    @Test
    fun `collapses a burst of reported failures into a single probe`() =
        runTest {
            val provider = newProvider()
            advanceUntilIdle()

            // First failure starts a probe; the rest arrive while that probe's debounce is running,
            // which is exactly what a screenful of parallel requests failing together looks like.
            provider.reportFailure()
            runCurrent()
            repeat(SCREENFUL_OF_REQUESTS) { provider.reportFailure() }
            advanceUntilIdle()

            // Initial availability probe + the first failure + one for the whole burst — not ten.
            coVerify(exactly = 3) { probe.isServerReachable() }
        }

    @Test
    fun `holds off a follow-up probe until the debounce window has passed`() =
        runTest {
            val provider = newProvider()
            advanceUntilIdle()

            provider.reportFailure()
            runCurrent()
            provider.reportFailure()
            advanceTimeBy(ConnectionStateProvider.PROBE_DEBOUNCE_MS / 2)
            runCurrent()

            coVerify(exactly = 2) { probe.isServerReachable() }

            advanceUntilIdle()

            coVerify(exactly = 3) { probe.isServerReachable() }
        }

    @Test
    fun `recovers to online when a refresh finds the server again`() =
        runTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()
            advanceUntilIdle()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            coEvery { probe.isServerReachable() } returns true
            provider.refresh()
            advanceUntilIdle()

            provider.state.value shouldBe ConnectionState.ONLINE
        }

    private companion object {
        /** Roughly the number of parallel requests a home screen fires. */
        const val SCREENFUL_OF_REQUESTS = 8
    }
}
