package dev.jellyboost.core.network.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Mocks the framework classes with MockK rather than pulling Robolectric into the build — the
 * `LicenceViewModelTest` precedent, and no module here uses Robolectric.
 *
 * What is worth pinning is the seam the Downloads screen depends on: that "metered" and "has
 * network" are read off **one** registration, and that an offline device reports *not* metered. The
 * second is a claim `ConnectivityMonitor.isMetered`'s KDoc makes in prose, so it owes a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidConnectivityMonitorTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val activeNetwork = mockk<Network>()
    private val callback = slot<ConnectivityManager.NetworkCallback>()

    private fun monitor(scope: CoroutineScope): AndroidConnectivityMonitor {
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.activeNetwork } returns activeNetwork
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        return AndroidConnectivityMonitor(context = context, appScope = scope)
    }

    /** `null` stands for a transport the system reports no capabilities for at all. */
    private fun seed(
        hasInternet: Boolean,
        notMetered: Boolean,
    ) {
        every { manager.getNetworkCapabilities(activeNetwork) } returns
            capabilities(hasInternet = hasInternet, notMetered = notMetered)
    }

    private fun capabilities(
        hasInternet: Boolean,
        notMetered: Boolean,
    ): NetworkCapabilities =
        mockk<NetworkCapabilities>().also {
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns hasInternet
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns notMetered
        }

    @Test
    fun `unmetered wi-fi is a network and is not metered`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = true, notMetered = true)
            val subject = monitor(scope)

            subject.hasNetwork.test { awaitItem() shouldBe true }
            subject.isMetered.test { awaitItem() shouldBe false }

            scope.cancel()
        }

    @Test
    fun `metered mobile data is a network and is metered`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = true, notMetered = false)
            val subject = monitor(scope)

            subject.hasNetwork.test { awaitItem() shouldBe true }
            subject.isMetered.test { awaitItem() shouldBe true }

            scope.cancel()
        }

    /**
     * The contract the waiting-for-Wi-Fi notice rests on: with nothing connected, "metered" is
     * `false`, so a screen can never tell an offline user the queue is waiting for Wi-Fi.
     */
    @Test
    fun `no network at all is not metered`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            every { manager.activeNetwork } returns null
            every { context.getSystemService(ConnectivityManager::class.java) } returns manager
            every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
            val subject = AndroidConnectivityMonitor(context = context, appScope = scope)

            subject.hasNetwork.test { awaitItem() shouldBe false }
            subject.isMetered.test { awaitItem() shouldBe false }

            scope.cancel()
        }

    /**
     * A transport carrying no `NET_CAPABILITY_INTERNET` is "no network" to [ConnectivityMonitor.hasNetwork],
     * so it must not read as a *metered* one either — the two answers may never disagree about whether a
     * transport counts at all.
     */
    @Test
    fun `a metered transport with no internet is neither a network nor metered`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = false, notMetered = false)
            val subject = monitor(scope)

            subject.hasNetwork.test { awaitItem() shouldBe false }
            subject.isMetered.test { awaitItem() shouldBe false }

            scope.cancel()
        }

    @Test
    fun `leaving wi-fi for mobile data flips metered without dropping the network`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = true, notMetered = true)
            val subject = monitor(scope)

            subject.isMetered.test {
                awaitItem() shouldBe false

                callback.captured.onCapabilitiesChanged(
                    activeNetwork,
                    capabilities(hasInternet = true, notMetered = false),
                )
                awaitItem() shouldBe true
            }
            // The network never went away; only what it costs changed.
            subject.hasNetwork.test { awaitItem() shouldBe true }

            scope.cancel()
        }

    @Test
    fun `losing the network reports neither a network nor a metered one`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = true, notMetered = false)
            val subject = monitor(scope)

            subject.isMetered.test {
                awaitItem() shouldBe true

                callback.captured.onLost(activeNetwork)
                awaitItem() shouldBe false
            }
            subject.hasNetwork.test { awaitItem() shouldBe false }

            scope.cancel()
        }

    /**
     * The KDoc's "one registration carrying both facts" is the reason the two flows are derived from a
     * shared upstream rather than each opening its own; collecting both must not cost two system
     * registrations.
     */
    @Test
    fun `both flows are served by a single system registration`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            seed(hasInternet = true, notMetered = true)
            val subject = monitor(scope)

            val network = backgroundScope.launch { subject.hasNetwork.collect { } }
            val metered = backgroundScope.launch { subject.isMetered.collect { } }
            runCurrent()

            verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }

            network.cancel()
            metered.cancel()
            scope.cancel()
        }

    /**
     * No connectivity service at all: a state that should not happen on a real device must not lock the
     * app offline forever, nor make it report a metered link it cannot see.
     */
    @Test
    fun `a device with no connectivity service is online and unmetered`() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            every { context.getSystemService(ConnectivityManager::class.java) } returns null
            val subject = AndroidConnectivityMonitor(context = context, appScope = scope)

            subject.hasNetwork.test { awaitItem() shouldBe true }
            subject.isMetered.test { awaitItem() shouldBe false }

            scope.cancel()
        }
}
