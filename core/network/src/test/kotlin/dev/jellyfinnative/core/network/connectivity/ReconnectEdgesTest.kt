package dev.jellyfinnative.core.network.connectivity

import app.cash.turbine.test
import dev.jellyfinnative.core.network.ConnectionState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [reconnectEdges] — the signal every screen refreshes itself on (M9).
 *
 * The two properties that matter are both about what it does *not* emit: nothing for the value the
 * flow already holds when a screen subscribes (the screen has just loaded), and nothing extra for a
 * connection that flaps.
 *
 * Each state change is followed by `runCurrent()`: a `StateFlow` conflates, so two assignments in a
 * row without letting the collector run would be one transition, and the test would be asserting
 * something other than what it says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectEdgesTest {
    @Test
    fun `an already-online connection is not a reconnect`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.reconnectEdges().test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an already-offline connection is not a reconnect either`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.reconnectEdges().test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `coming back online emits exactly once`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.reconnectEdges().test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `losing the connection emits nothing`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.reconnectEdges().test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `swapping between two offline reasons is not a reconnect`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_NO_NETWORK)

            state.reconnectEdges().test {
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
    fun `a connection that flaps twice refreshes exactly twice`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.ONLINE)

            state.reconnectEdges().test {
                runCurrent()
                // Walking out of Wi-Fi range and back, twice: two refreshes, not one per state the
                // connection passed through on the way.
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe Unit
                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a repeated online state does not re-emit`() =
        runTest {
            val state = MutableStateFlow(ConnectionState.OFFLINE_FORCED)

            state.reconnectEdges().test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()
                awaitItem() shouldBe Unit

                state.value = ConnectionState.ONLINE
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
