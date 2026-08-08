package dev.jellyboost.data

import app.cash.turbine.test
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

    /**
     * [ConnectivityRefresher.reloadOnChange] is the shape five ViewModels used to spell out for
     * themselves (audit 2026-08-08, DUP-8). What is pinned here is what they collectively relied
     * on: a reload per change in either direction, the predicate the two variants pass, and the
     * collection dying with the scope — a ViewModel's `viewModelScope` in production.
     */
    @Nested
    inner class ReloadOnChange {
        // `by lazy`, not a plain field: JUnit constructs a @Nested instance before the outer
        // @BeforeEach runs, and `ConnectivityRefresher` reads `provider.state` in its constructor.
        private val refresher by lazy { ConnectivityRefresher(provider) }
        private var reloads = 0

        @Test
        fun `reloads on a change in either direction and not on the state it starts with`() =
            runTest {
                refresher.reloadOnChange(backgroundScope) { reloads++ }
                runCurrent()
                reloads shouldBe 0

                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()
                reloads shouldBe 1

                state.value = ConnectionState.ONLINE
                runCurrent()
                reloads shouldBe 2
            }

        @Test
        fun `reloads on a reconfirmation, which no edge would ever report`() =
            runTest {
                refresher.reloadOnChange(backgroundScope) { reloads++ }
                runCurrent()

                reconfirmations.tryEmit(Unit)
                runCurrent()

                reloads shouldBe 1
            }

        @Test
        fun `a false predicate skips the reload without ending the collection`() =
            runTest {
                var allowed = false
                refresher.reloadOnChange(backgroundScope, onlyIf = { allowed }) { reloads++ }
                runCurrent()

                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()
                reloads shouldBe 0

                // The screen has since asked for the data the change would invalidate — the
                // `LibraryViewModel`/`SearchViewModel` case. The next change must still arrive.
                allowed = true
                state.value = ConnectionState.ONLINE
                runCurrent()
                reloads shouldBe 1
            }

        @Test
        fun `the predicate is read at each change, not captured once`() =
            runTest {
                var allowed = true
                refresher.reloadOnChange(backgroundScope, onlyIf = { allowed }) { reloads++ }
                runCurrent()

                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()
                allowed = false
                state.value = ConnectionState.ONLINE
                runCurrent()

                reloads shouldBe 1
            }

        @Test
        fun `cancelling the scope stops the reloads`() =
            runTest {
                val job = refresher.reloadOnChange(backgroundScope) { reloads++ }
                runCurrent()

                job.cancelAndJoin()
                state.value = ConnectionState.OFFLINE_NO_NETWORK
                runCurrent()

                reloads shouldBe 0
            }
    }
}
