package dev.jellyboost.feature.auth

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ServerDiscoveryRepository
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.DiscoveredServer
import dev.jellyboost.core.network.model.ResolvedServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

/** Unit tests for the ServerSetup state holder. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val discoveryRepository = mockk<ServerDiscoveryRepository>()
    private val pendingServerStore = PendingServerStore()
    private val sessionRepository = mockk<SessionRepository>()

    private fun viewModel(
        discovered: Flow<DiscoveredServer> = emptyFlow(),
        sessionWasLost: Boolean = false,
    ): ServerSetupViewModel {
        every { discoveryRepository.discoverLocalServers() } returns discovered
        every { sessionRepository.consumeInvoluntarySignOut() } returns sessionWasLost
        return ServerSetupViewModel(
            serverDiscoveryRepository = discoveryRepository,
            pendingServerStore = pendingServerStore,
            sessionRepository = sessionRepository,
        )
    }

    @Test
    @DisplayName("a session lost to an unreadable credential store is said out loud, not implied")
    fun involuntarySignOutIsSurfaced() =
        runTest {
            // Without this state, the store wiping itself and the user landing here would be
            // indistinguishable from a first run.
            val viewModel = viewModel(sessionWasLost = true)
            advanceUntilIdle()

            viewModel.uiState.value.sessionWasLost shouldBe true
        }

    @Test
    @DisplayName("a first run says nothing about a lost session")
    fun firstRunSaysNothing() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.sessionWasLost shouldBe false
        }

    @Test
    @DisplayName("discovered servers accumulate in arrival order and repeats are ignored")
    fun discoveryAccumulatesAndDeduplicates() =
        runTest {
            val viewModel =
                viewModel(flowOf(LIVING_ROOM, ATTIC, LIVING_ROOM.copy(name = "Living Room (again)")))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.discoveredServers shouldContainExactly listOf(LIVING_ROOM, ATTIC)
            state.isDiscovering shouldBe false
        }

    @Test
    @DisplayName("a discovery failure is swallowed and only ends the search")
    fun discoveryFailureDoesNotBreakTheScreen() =
        runTest {
            val viewModel =
                viewModel(
                    flow {
                        emit(LIVING_ROOM)
                        error("socket closed")
                    },
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.discoveredServers shouldContainExactly listOf(LIVING_ROOM)
            state.isDiscovering shouldBe false
            state.error shouldBe null
        }

    @Test
    @DisplayName("a resolved address is stored for Login and produces a navigation event")
    fun connectSuccessPopulatesTheStoreAndNavigates() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Success(RESOLVED)
            val viewModel = viewModel()

            viewModel.navigateToLogin.test {
                viewModel.onAddressChange(ADDRESS)
                viewModel.connect()
                advanceUntilIdle()

                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            pendingServerStore.server shouldBe RESOLVED
            viewModel.uiState.value.error shouldBe null
        }

    @Test
    @DisplayName("tapping a discovered server resolves its address the same way")
    fun tappingADiscoveredServerConnects() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(LIVING_ROOM.address) } returns
                AppResult.Success(RESOLVED)
            val viewModel = viewModel(flowOf(LIVING_ROOM))

            viewModel.navigateToLogin.test {
                viewModel.connectTo(LIVING_ROOM.address)
                advanceUntilIdle()

                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            pendingServerStore.server shouldBe RESOLVED
            viewModel.uiState.value.address shouldBe LIVING_ROOM.address
        }

    @Test
    @DisplayName("a failed resolution surfaces the unreachable/incompatible split")
    fun connectFailureExposesThePartitionedCopy() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Failure(
                    AppError.ServerResolution(
                        unreachableAddresses = listOf("https://media.example.com"),
                        incompatibleAddresses = listOf("http://media.example.com:8096"),
                    ),
                )
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            advanceUntilIdle()

            val error = viewModel.uiState.value.error
            error.shouldBeInstanceOf<AuthErrorMessage.ServerResolution>()
            error.unreachable shouldContainExactly listOf("https://media.example.com")
            error.incompatible shouldContainExactly listOf("http://media.example.com:8096")
            pendingServerStore.server shouldBe null
        }

    @Test
    @DisplayName("an input with no usable candidate at all falls back to the generic copy")
    fun connectFailureWithoutCandidates() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Failure(AppError.ServerResolution())
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            advanceUntilIdle()

            viewModel.uiState.value.error shouldBe AuthErrorMessage.CannotConnect
        }

    @Test
    @DisplayName("the connecting flag is raised for the probe and lowered again afterwards")
    fun connectTogglesTheLoadingFlag() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } coAnswers {
                delay(PROBE_MILLIS)
                AppResult.Success(RESOLVED)
            }
            val viewModel = viewModel()

            viewModel.uiState.test {
                awaitItem().isConnecting shouldBe false

                viewModel.connectTo(ADDRESS)
                awaitItem().isConnecting shouldBe true

                advanceUntilIdle()
                awaitItem().isConnecting shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("the address field is inert while a probe is in flight")
    fun addressCannotChangeMidProbe() =
        runTest {
            // The screen keeps the field *enabled* through the probe so a TalkBack user does not
            // lose accessibility focus the instant they press Connect; this is what makes
            // "enabled" safe — the state cannot drift away from the address being resolved.
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } coAnswers {
                delay(PROBE_MILLIS)
                AppResult.Success(RESOLVED)
            }
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            viewModel.uiState.value.isConnecting shouldBe true

            viewModel.onAddressChange("something.else")

            viewModel.uiState.value.address shouldBe ADDRESS
            advanceUntilIdle()
        }

    @Test
    @DisplayName("the address field takes edits again once the probe has answered")
    fun addressChangesAgainAfterTheProbe() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Failure(AppError.ServerResolution())
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            advanceUntilIdle()
            viewModel.onAddressChange("something.else")

            viewModel.uiState.value.address shouldBe "something.else"
            // Editing after a failure also clears the failure.
            viewModel.uiState.value.error shouldBe null
        }

    @Test
    @DisplayName("a blank address never reaches the repository")
    fun blankAddressIsIgnored() =
        runTest {
            val viewModel = viewModel()

            viewModel.onAddressChange("   ")
            viewModel.connect()
            advanceUntilIdle()

            viewModel.uiState.value.isConnecting shouldBe false
            viewModel.uiState.value.canConnect shouldBe false
        }

    @Test
    @DisplayName("a public server that answered over plain http warns instead of moving on")
    fun cleartextPublicServerWarnsBeforeNavigating() =
        runTest {
            // The address the app is about to send the token to is the *resolved* one, and here
            // it came back cleartext on a host that is not on this network.
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Success(CLEARTEXT_PUBLIC)
            val viewModel = viewModel()

            viewModel.navigateToLogin.test {
                viewModel.connectTo(ADDRESS)
                advanceUntilIdle()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            val state = viewModel.uiState.value
            state.cleartextWarningHost shouldBe "media.example.com"
            state.isConnecting shouldBe false
            state.error shouldBe null
            // Nothing is handed to Login until the user has seen the warning.
            pendingServerStore.server shouldBe null
        }

    @Test
    @DisplayName("pressing Connect again is the acknowledgement, and costs no second round-trip")
    fun secondConnectAcknowledgesTheWarning() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Success(CLEARTEXT_PUBLIC)
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            advanceUntilIdle()

            viewModel.navigateToLogin.test {
                viewModel.connect()
                advanceUntilIdle()

                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            pendingServerStore.server shouldBe CLEARTEXT_PUBLIC
            viewModel.uiState.value.cleartextWarningHost shouldBe null
            // The server had already answered; acknowledging must not ask it again.
            coVerify(exactly = 1) { discoveryRepository.resolveServerAddress(ADDRESS) }
        }

    @Test
    @DisplayName("typing a different address retracts the warning and the acknowledgement with it")
    fun editingTheAddressRetractsTheWarning() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(ADDRESS) } returns
                AppResult.Success(CLEARTEXT_PUBLIC)
            coEvery { discoveryRepository.resolveServerAddress(OTHER_ADDRESS) } returns
                AppResult.Success(RESOLVED)
            val viewModel = viewModel()

            viewModel.connectTo(ADDRESS)
            advanceUntilIdle()
            viewModel.uiState.value.cleartextWarningHost shouldBe "media.example.com"

            viewModel.onAddressChange(OTHER_ADDRESS)
            viewModel.uiState.value.cleartextWarningHost shouldBe null

            // And the next Connect probes the new address rather than proceeding to the old server.
            viewModel.connect()
            advanceUntilIdle()
            pendingServerStore.server shouldBe RESOLVED
        }

    @Test
    @DisplayName("a LAN server over http is not warned about, however the address was typed")
    fun privateCleartextServerIsNotWarnedAbout() =
        runTest {
            coEvery { discoveryRepository.resolveServerAddress(LIVING_ROOM.address) } returns
                AppResult.Success(CLEARTEXT_PRIVATE)
            val viewModel = viewModel()

            viewModel.navigateToLogin.test {
                viewModel.connectTo(LIVING_ROOM.address)
                advanceUntilIdle()

                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.uiState.value.cleartextWarningHost shouldBe null
            pendingServerStore.server shouldBe CLEARTEXT_PRIVATE
        }

    private companion object {
        const val ADDRESS = "media.example.com"
        const val OTHER_ADDRESS = "other.example.com"
        const val PROBE_MILLIS = 250L

        val LIVING_ROOM =
            DiscoveredServer(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                name = "Living Room",
                address = "http://192.168.1.10:8096",
            )

        val ATTIC =
            DiscoveredServer(
                id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                name = "Attic",
                address = "http://192.168.1.11:8096",
            )

        val RESOLVED =
            ResolvedServer(
                serverId = LIVING_ROOM.id,
                name = "Living Room",
                version = "10.11.0",
                address = "https://media.example.com",
            )

        /** The cleartext-warning case: a port-forwarded server, reached in the clear. */
        val CLEARTEXT_PUBLIC = RESOLVED.copy(address = "http://media.example.com:8096")

        /** The overwhelmingly common case, which must stay silent. */
        val CLEARTEXT_PRIVATE = RESOLVED.copy(address = "http://192.168.1.10:8096")
    }
}
