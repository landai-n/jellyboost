package dev.jellyfinnative.data

import app.cash.turbine.test
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReconnectRefresher] — the `:data`-side handle feature ViewModels inject.
 *
 * The edge semantics themselves belong to `ReconnectEdgesTest`; what is tested here is that this
 * class passes the provider's state through unchanged and adds nothing of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectRefresherTest {
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val provider = mockk<ConnectionStateProvider>()

    @BeforeEach
    fun setUp() {
        every { provider.state } returns state
    }

    @Test
    fun `says nothing about the connection a screen starts with`() =
        runTest {
            ReconnectRefresher(provider).reconnected.test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fires when the server becomes reachable again`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            ReconnectRefresher(provider).reconnected.test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `does not fire when the connection is lost`() =
        runTest {
            ReconnectRefresher(provider).reconnected.test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
