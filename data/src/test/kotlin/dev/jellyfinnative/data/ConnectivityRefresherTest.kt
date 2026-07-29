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
 * Unit tests for [ConnectivityRefresher] — the `:data`-side handle feature ViewModels inject.
 *
 * The edge semantics themselves belong to `ConnectivityEdgesTest`; what is tested here is that this
 * class passes the provider's state through unchanged, in both directions, and adds nothing of its
 * own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityRefresherTest {
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val provider = mockk<ConnectionStateProvider>()

    @BeforeEach
    fun setUp() {
        every { provider.state } returns state
    }

    @Test
    fun `says nothing about the connection a screen starts with`() =
        runTest {
            ConnectivityRefresher(provider).connectivityChanged.test {
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fires when the server becomes reachable again`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            ConnectivityRefresher(provider).connectivityChanged.test {
                runCurrent()
                state.value = ConnectionState.ONLINE
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fires when the connection is lost`() =
        runTest {
            ConnectivityRefresher(provider).connectivityChanged.test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fires when the user pins offline mode`() =
        runTest {
            ConnectivityRefresher(provider).connectivityChanged.test {
                runCurrent()
                state.value = ConnectionState.OFFLINE_FORCED
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
