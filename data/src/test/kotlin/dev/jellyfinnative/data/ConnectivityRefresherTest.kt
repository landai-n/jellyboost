package dev.jellyfinnative.data

import app.cash.turbine.test
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /** The provider's "reachable all along, but somebody fell back to Room" tick. */
    private val reconfirmations =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val provider = mockk<ConnectionStateProvider>()

    @BeforeEach
    fun setUp() {
        every { provider.state } returns state
        every { provider.serverReconfirmed } returns reconfirmations
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

    /**
     * The state never moved here — it read online the whole time — so the edges have nothing to
     * say, and the screen that fell back to downloads-only data would sit on it forever.
     */
    @Test
    fun `fires when the server is reconfirmed after a request fell back`() =
        runTest {
            ConnectivityRefresher(provider).connectivityChanged.test {
                runCurrent()
                reconfirmations.tryEmit(Unit)
                runCurrent()

                awaitItem() shouldBe Unit
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reports the current online state`() {
        val refresher = ConnectivityRefresher(provider)

        refresher.isOnline shouldBe true
    }

    @Test
    fun `reports every offline reason as not online`() {
        val refresher = ConnectivityRefresher(provider)

        listOf(
            ConnectionState.OFFLINE_NO_NETWORK,
            ConnectionState.OFFLINE_SERVER_UNREACHABLE,
            ConnectionState.OFFLINE_FORCED,
        ).forEach { offline ->
            state.value = offline

            // Read live, not captured at construction: the same instance answers for the app's
            // whole lifetime, and a stale `true` would let a screen fire doomed requests.
            refresher.isOnline shouldBe false
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
