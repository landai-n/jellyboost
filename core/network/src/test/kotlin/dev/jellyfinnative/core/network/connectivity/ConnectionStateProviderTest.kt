package dev.jellyfinnative.core.network.connectivity

import app.cash.turbine.test
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.TestFixtures.SERVER_ID
import dev.jellyfinnative.core.network.TestFixtures.SERVER_NAME
import dev.jellyfinnative.core.network.TestFixtures.SERVER_VERSION
import dev.jellyfinnative.core.network.TestFixtures.USER_ID
import dev.jellyfinnative.core.network.TestFixtures.USER_NAME
import dev.jellyfinnative.core.network.model.SessionState
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

    /** Starts at [SessionState.Unknown], exactly as it does at app launch. */
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

            // M7's download preference is irrelevant to connectivity; it is here only because the
            // interface every module sees now declares it.
            override val downloadOverWifiOnly: Flow<Boolean> = MutableStateFlow(true)

            override suspend fun setDownloadOverWifiOnly(enabled: Boolean) = Unit

            override val downloadQuality: Flow<DownloadQuality> = MutableStateFlow(DownloadQuality.ORIGINAL)

            override suspend fun setDownloadQuality(quality: DownloadQuality) = Unit

            override val downloadStorageVolumeId: Flow<String?> = MutableStateFlow(null)

            override suspend fun setDownloadStorageVolumeId(volumeId: String?) = Unit

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
     * `runTest`, with the provider's application scope cancelled before the framework drains the
     * scheduler on its way out.
     *
     * The unreachable re-probe is an endless timer by design, and `runTest` finishes by running the
     * scheduler until it is idle — which, with that timer armed, is never: the test would spin
     * through virtual time probing forever instead of passing or failing.
     */
    private fun connectivityTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                applicationScope?.cancel()
            }
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
            sessionStateHolder = sessionStateHolder,
            probe = probe,
            appPreferences = preferences,
            scope = scope,
        )
    }

    /**
     * Builds the provider and takes it through the cold start a signed-in user gets: launch, then
     * `restoreSession()` publishing a session, which is the first moment a probe may run at all.
     *
     * Costs exactly one probe — the one the restored session asks for.
     */
    private fun TestScope.newSignedInProvider(): ConnectionStateProvider {
        val provider = newProvider()
        settle()
        sessionStateHolder.update(loggedIn())
        settle()
        return provider
    }

    /**
     * Runs everything that is due, plus every probe queued behind the debounce, and stops well short
     * of the first [ConnectionStateProvider.UNREACHABLE_REPROBE_MS] tick.
     *
     * `advanceUntilIdle` cannot be used once the state may settle on
     * [ConnectionState.OFFLINE_SERVER_UNREACHABLE]: the re-probe loop is an endless timer by design,
     * so "until the scheduler runs dry" never arrives and the test would hang instead of fail.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    /** Advances past the next unattended re-probe tick and lets the probe it asks for run. */
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

    // ---- state resolution ---------------------------------------------------------------------

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

    // ---- probing ------------------------------------------------------------------------------

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

            // First failure starts a probe; the rest arrive while that probe's debounce is running,
            // which is exactly what a screenful of parallel requests failing together looks like.
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

    // ---- probing on session changes -------------------------------------------------------------

    /**
     * The fresh-install bug, in one test: signed out, the probe has no server to try and correctly
     * answers "unreachable", and until this wiring existed nothing ever re-asked — the user signed
     * in successfully and the app still claimed it could not reach the server until restart.
     */
    @Test
    fun `re-probes when a session appears, with no connectivity change at all`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newProvider()
            settle()
            sessionStateHolder.update(SessionState.LoggedOut)
            settle()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            // Sign-in: a server to probe now exists, and the network never moved.
            coEvery { probe.isServerReachable() } returns true
            sessionStateHolder.update(loggedIn())
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    /**
     * The cold-start bug: `restoreSession()` had not published anything yet, so the launch probe
     * asked about a server nobody was signed in to, got `false` for it, and put the whole first
     * screen on offline data. Until the session is known there is nothing to learn, so the app keeps
     * the optimism it launched with.
     */
    @Test
    fun `keeps the launch optimism while the session is still unknown`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false

            val provider = newProvider()
            settle()

            provider.state.value shouldBe ConnectionState.ONLINE
            coVerify(exactly = 0) { probe.isServerReachable() }
        }

    /** …and the moment the restore lands, the probe runs and whatever it finds is the answer. */
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

            // With nobody signed in the probe has no address to try and says so.
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

            // Same user on the same server — only the reported server version moved, which changes
            // nothing about what the probe would try.
            sessionStateHolder.update(loggedIn(serverVersion = "10.11.1"))
            settle()

            // The restored-session probe, and nothing for the republished session.
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

    // ---- a throwing probe must not take the loop with it (STAB-07) --------------------------------

    /**
     * The probe consumer is the app's only offline detector and it runs for the life of the
     * process. Before this guard one throw ended the loop for good: the state froze on its last
     * verdict and no *Retry* tap, reconnect or sign-in could ever move it again.
     */
    @Test
    fun `survives a probe that throws and keeps answering later ones`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
            val provider = newSignedInProvider()

            // The loop is still there to serve the next request, and it answers it.
            coEvery { probe.isServerReachable() } returns false
            provider.refresh()
            settle()

            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE
            coVerify(exactly = 2) { probe.isServerReachable() }
        }

    /**
     * A probe that threw learnt nothing, so it must not be read as "unreachable" — that would put
     * an offline banner in front of the user on the strength of a bug.
     */
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

    /** The state flow itself must stay collectable — a dead loop used to strand every collector. */
    @Test
    fun `leaves the state flow alive and collectable after a throwing probe`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } throws IllegalStateException("probe blew up")
            val provider = newSignedInProvider()

            provider.state.test {
                awaitItem() shouldBe ConnectionState.ONLINE

                hasNetworkFlow.value = false
                settle()

                // A live collector still receives the next change, which is the whole claim.
                awaitItem() shouldBe ConnectionState.OFFLINE_NO_NETWORK
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- reconfirming a server the state never doubted -----------------------------------------

    /**
     * `DelegatingJellyfinRepository` can fall back to Room while the state still reads online, and
     * the probe that follows leaves the verdict exactly where it was — no edge, and a screen left
     * showing downloads-only data. This tick is the only thing that can tell it to load again.
     */
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

    /**
     * A recovery that *changes* the verdict already produces a state edge, and screens refresh on
     * those; ticking as well would make every one of them fetch twice.
     */
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

    /** A probe that threw learnt nothing, so the reported failure is still owed an answer. */
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

    // ---- digging out of an unreachable verdict on its own ---------------------------------------

    /**
     * Once offline, every repository call goes straight to Room, so no failure can be reported and
     * nothing else would ever re-ask. Without this the verdict — right or wrong — lasted until the
     * user tapped *Retry* or left the app and came back.
     */
    @Test
    fun `comes back online by itself when a later re-probe finds the server`() =
        connectivityTest {
            coEvery { probe.isServerReachable() } returns false
            val provider = newSignedInProvider()
            provider.state.value shouldBe ConnectionState.OFFLINE_SERVER_UNREACHABLE

            // Nothing external moves: no network change, no session change, no refresh, no tap.
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

            // A failed re-probe writes `false` over `false` and moves no state — the ticking has to
            // survive that, or one failure would end the recovery.
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
