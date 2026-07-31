package dev.jellyboost.core.network.connectivity

import app.cash.turbine.test
import dev.jellyboost.core.network.ConnectionState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [onlineStateChanges] — the signal every screen refreshes itself on (M9).
 *
 * Three properties: it reports a change in *either* direction, it says nothing about the value the
 * flow already holds when a screen subscribes (that screen has just loaded), and it says nothing
 * extra for a connection flapping between two states that are equally offline.
 *
 * Each state change is followed by `runCurrent()`: a `StateFlow` conflates, so two assignments in a
 * row without letting the collector run would be one transition, and the test would be asserting
 * something other than what it says.
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
                // Walking out of Wi-Fi range and back, twice: one emission per crossing, not one
                // per state the connection passed through on the way.
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
}
