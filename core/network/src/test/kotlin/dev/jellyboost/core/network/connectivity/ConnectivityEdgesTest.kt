package dev.jellyboost.core.network.connectivity

import app.cash.turbine.test
import dev.jellyboost.core.network.ConnectionState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Each state change is followed by `runCurrent()`: a `StateFlow` conflates, so two assignments in a row
 * without letting the collector run would be one transition, and the test would assert something else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityEdgesTest {
    @Test
    fun `an already-online connection is not a change`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.onlineStateChanges().test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an already-offline connection is not a change either`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.onlineStateChanges().test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `coming back online emits true exactly once`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.onlineStateChanges().test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe true
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `losing the connection emits false`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.onlineStateChanges().test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()

                awaitItem() shouldBe false
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the user pinning offline mode is a change like any other`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.onlineStateChanges().test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_FORCED
                runCurrent()

                awaitItem() shouldBe false
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `swapping between two offline reasons is not a change`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.onlineStateChanges().test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()
                state.value = ConnectionState.OFFLINE_FORCED
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a connection that flaps twice emits once per crossing, alternating`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.onlineStateChanges().test {
                runCurrent()
                // Walking out of Wi-Fi range and back, twice: one emission per crossing, not one per state.
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe false
                awaitItem() shouldBe true
                awaitItem() shouldBe false
                awaitItem() shouldBe true
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a repeated online state does not re-emit`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_FORCED)

            state.onlineStateChanges().test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()
                awaitItem() shouldBe true

                state.value = ConnectionState.ONLINE
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * [onEachOnlineStretch]'s contract is the *opposite* of [onlineStateChanges] on exactly one point: it acts
     * on the value the flow already holds. That is the app-start check its two callers depend on.
     */
    @Nested
    inner class OnEachOnlineStretch {
        private val state = MutableStateFlow(ConnectionState.ONLINE)
        private val provider =
            mockk<ConnectionStateProvider>().also { every { it.state } returns state }

        private val online = mutableListOf<Int>()
        private val offline = mutableListOf<Int>()
        private var tick = 0

        private fun TestScope.collectStretches(): Job =
            backgroundScope.launch {
                provider.onEachOnlineStretch(
                    onOffline = { offline += ++tick },
                    onOnline = { online += ++tick },
                )
            }

        @Test
        fun `an app that starts online runs the online block immediately`() =
            runTest {
                collectStretches()
                runCurrent()

                online shouldBe listOf(1)
                offline shouldBe emptyList()
            }

        @Test
        fun `an app that starts offline runs the offline block, not the online one`() =
            runTest {
                state.value = ConnectionState.OFFLINE_NO_NETWORK

                collectStretches()
                runCurrent()

                online shouldBe emptyList()
                offline shouldBe listOf(1)
            }

        @Test
        fun `every return to the network is another stretch`() =
            runTest {
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                collectStretches()
                runCurrent()

                state.value = ConnectionState.ONLINE
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                // Interleaved, in order: offline (start), online, offline, online.
                offline shouldBe listOf(1, 3)
                online shouldBe listOf(2, 4)
            }

        @Test
        fun `a flapping reason is not a new stretch`() =
            runTest {
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                collectStretches()
                runCurrent()

                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()
                state.value = ConnectionState.OFFLINE_FORCED
                runCurrent()

                offline shouldBe listOf(1)
                online shouldBe emptyList()
            }

        @Test
        fun `the offline block is optional`() =
            runTest {
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                backgroundScope.launch { provider.onEachOnlineStretch { online += ++tick } }
                runCurrent()

                state.value = ConnectionState.ONLINE
                runCurrent()

                online shouldBe listOf(1)
            }

        @Test
        fun `cancelling the scope stops the collection`() =
            runTest {
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                val job = collectStretches()
                runCurrent()

                job.cancelAndJoin()
                state.value = ConnectionState.ONLINE
                runCurrent()

                online shouldBe emptyList()
            }
    }
}
