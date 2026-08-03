package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Unit tests for [ConnectionViewModel] — a deliberately thin view over the singleton
 * [ConnectionStateProvider], so what is pinned is exactly the delegation: state passes through
 * untouched, and the two user actions land on the right collaborator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val stateFlow = MutableStateFlow(ConnectionState.ONLINE)
    private val connectionStateProvider =
        mockk<ConnectionStateProvider> {
            every { state } returns stateFlow
            justRun { refresh() }
        }
    private val appPreferences = mockk<AppPreferences>(relaxed = true)

    private fun viewModel() = ConnectionViewModel(connectionStateProvider, appPreferences)

    @Test
    @DisplayName("the provider's connection state is passed straight through")
    fun passesConnectionStateThrough() =
        runTest {
            viewModel().connectionState.test {
                awaitItem() shouldBe ConnectionState.ONLINE

                stateFlow.value = ConnectionState.OFFLINE_NO_NETWORK
                awaitItem() shouldBe ConnectionState.OFFLINE_NO_NETWORK

                stateFlow.value = ConnectionState.OFFLINE_FORCED
                awaitItem() shouldBe ConnectionState.OFFLINE_FORCED
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("toggling force-offline persists the exact value, in both directions")
    fun persistsForceOffline() =
        runTest {
            val viewModel = viewModel()

            viewModel.setForceOffline(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { appPreferences.setForceOffline(true) }

            viewModel.setForceOffline(false)
            advanceUntilIdle()
            coVerify(exactly = 1) { appPreferences.setForceOffline(false) }
        }

    @Test
    @DisplayName("refresh delegates to the provider's debounced probe")
    fun refreshDelegates() =
        runTest {
            viewModel().refresh()

            verify(exactly = 1) { connectionStateProvider.refresh() }
        }
}
