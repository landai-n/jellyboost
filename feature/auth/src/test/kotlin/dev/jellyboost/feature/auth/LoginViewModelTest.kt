package dev.jellyboost.feature.auth

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.AuthRepository
import dev.jellyboost.core.network.model.AuthenticatedSession
import dev.jellyboost.core.network.model.LoginContext
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.network.model.QuickConnectSession
import dev.jellyboost.core.network.model.QuickConnectState
import dev.jellyboost.core.network.model.ResolvedServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

/** Unit tests for the Login state holder — password sign-in and Quick Connect. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val authRepository = mockk<AuthRepository>()
    private val pendingServerStore = PendingServerStore()

    @BeforeEach
    fun setUp() {
        pendingServerStore.set(SERVER)
        coEvery { authRepository.fetchLoginContext(SERVER) } returns AppResult.Success(LOGIN_CONTEXT)
    }

    private fun viewModel() = LoginViewModel(authRepository = authRepository, pendingServerStore = pendingServerStore)

    @Test
    @DisplayName("the login context populates users, disclaimer and the Quick Connect flag")
    fun initLoadsTheLoginContext() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            state.isLoadingContext shouldBe false
            state.serverName shouldBe SERVER.name
            state.serverVersion shouldBe SERVER.version
            state.publicUsers shouldContainExactly listOf(PUBLIC_USER)
            state.loginDisclaimer shouldBe "Be nice."
            state.quickConnectEnabled shouldBe true
        }

    @Test
    @DisplayName("opening Login without a pending server sends the user back to server setup")
    fun missingServerNavigatesBack() =
        runTest {
            pendingServerStore.clear()
            val viewModel = viewModel()

            viewModel.navigationEvents.test {
                awaitItem() shouldBe LoginNavigationEvent.ServerMissing
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.uiState.value.isLoadingContext shouldBe false
        }

    @Test
    @DisplayName("tapping a public user pre-fills the username field")
    fun publicUserPreFillsTheUsername() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onPublicUserSelected(PUBLIC_USER)

            viewModel.uiState.value.username shouldBe PUBLIC_USER.name
        }

    @Test
    @DisplayName("a public user with an image tag gets a sized Primary-image URL")
    fun avatarUrlIsBuiltFromTheImageTag() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.avatarUrlFor(USER_WITH_AVATAR) shouldBe
                "https://media.example.com/Users/$USER_ID/Images/Primary?tag=$IMAGE_TAG&maxWidth=168"
        }

    @Test
    @DisplayName("a public user without an image tag has no avatar URL, so the letter fallback stands")
    fun avatarUrlIsNullWithoutAnImageTag() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.uiState.value.avatarUrlFor(PUBLIC_USER) shouldBe null
        }

    @Test
    @DisplayName("a server address with a trailing slash does not produce a doubled slash")
    fun avatarUrlToleratesATrailingSlash() =
        runTest {
            publicUserAvatarUrl("https://media.example.com/", USER_WITH_AVATAR) shouldBe
                "https://media.example.com/Users/$USER_ID/Images/Primary?tag=$IMAGE_TAG&maxWidth=168"
        }

    @Test
    @DisplayName("no known server address means no avatar URL")
    fun avatarUrlIsNullWithoutAServerAddress() =
        runTest {
            publicUserAvatarUrl(null, USER_WITH_AVATAR) shouldBe null
        }

    @Test
    @DisplayName("a successful password sign-in clears the pending server and navigates home")
    fun passwordLoginSucceeds() =
        runTest {
            coEvery { authRepository.loginWithPassword(SERVER, USER_NAME, PASSWORD) } returns
                AppResult.Success(SESSION)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.navigationEvents.test {
                viewModel.onUsernameChange("  $USER_NAME  ")
                viewModel.onPasswordChange(PASSWORD)
                viewModel.signIn()
                advanceUntilIdle()

                awaitItem() shouldBe LoginNavigationEvent.LoggedIn
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { authRepository.loginWithPassword(SERVER, USER_NAME, PASSWORD) }
            pendingServerStore.server shouldBe null
            viewModel.uiState.value.password shouldBe ""
            viewModel.uiState.value.isSigningIn shouldBe false
        }

    @Test
    @DisplayName("rejected credentials produce the wrong-username-or-password copy")
    fun passwordLoginRejected() =
        runTest {
            coEvery { authRepository.loginWithPassword(SERVER, USER_NAME, PASSWORD) } returns
                AppResult.Failure(AppError.Unauthorized())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onUsernameChange(USER_NAME)
            viewModel.onPasswordChange(PASSWORD)
            viewModel.signIn()
            advanceUntilIdle()

            viewModel.uiState.value.error shouldBe AuthErrorMessage.InvalidCredentials
            viewModel.uiState.value.isSigningIn shouldBe false
            pendingServerStore.server shouldBe SERVER
        }

    @Test
    @DisplayName("a transport failure during sign-in is reported as a connection problem")
    fun passwordLoginTransportFailure() =
        runTest {
            coEvery { authRepository.loginWithPassword(SERVER, USER_NAME, PASSWORD) } returns
                AppResult.Failure(AppError.Network())
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onUsernameChange(USER_NAME)
            viewModel.onPasswordChange(PASSWORD)
            viewModel.signIn()
            advanceUntilIdle()

            viewModel.uiState.value.error shouldBe AuthErrorMessage.CannotConnect
        }

    @Test
    @DisplayName("the sign-in button is inert without a username")
    fun signInRequiresAUsername() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.signIn()
            advanceUntilIdle()

            viewModel.uiState.value.canSignIn shouldBe false
            coVerify(exactly = 0) { authRepository.loginWithPassword(any(), any(), any()) }
        }

    @Test
    @DisplayName("LoginUiState.toString() never prints the password (audit SEC-09)")
    fun toStringRedactsThePassword() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onUsernameChange(USER_NAME)
            viewModel.onPasswordChange(PASSWORD)

            viewModel.uiState.value.toString() shouldNotContain PASSWORD
        }

    @Test
    @DisplayName("Quick Connect shows the code, waits for approval and then exchanges the secret")
    fun quickConnectHappyPath() =
        runTest {
            coEvery { authRepository.initiateQuickConnect() } returns
                AppResult.Success(QuickConnectSession(secret = SECRET, code = CODE))
            every { authRepository.observeQuickConnectState(SECRET) } returns
                pollingFlow(QuickConnectState.Approved)
            coEvery { authRepository.loginWithQuickConnect(SERVER, SECRET) } returns
                AppResult.Success(SESSION)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.navigationEvents.test {
                viewModel.startQuickConnect()

                viewModel.uiState.value.quickConnect
                    ?.code shouldBe CODE
                viewModel.uiState.value.quickConnect
                    ?.isWaiting shouldBe true

                advanceUntilIdle()
                awaitItem() shouldBe LoginNavigationEvent.LoggedIn
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { authRepository.loginWithQuickConnect(SERVER, SECRET) }
            viewModel.uiState.value.quickConnect shouldBe null
            pendingServerStore.server shouldBe null
        }

    @Test
    @DisplayName("an expired Quick Connect request closes the sheet with a retry hint")
    fun quickConnectExpires() =
        runTest {
            coEvery { authRepository.initiateQuickConnect() } returns
                AppResult.Success(QuickConnectSession(secret = SECRET, code = CODE))
            every { authRepository.observeQuickConnectState(SECRET) } returns
                pollingFlow(QuickConnectState.Expired)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startQuickConnect()
            advanceUntilIdle()

            viewModel.uiState.value.quickConnect shouldBe null
            viewModel.uiState.value.error shouldBe AuthErrorMessage.QuickConnectExpired
            coVerify(exactly = 0) { authRepository.loginWithQuickConnect(any(), any()) }
        }

    @Test
    @DisplayName("a polling failure closes the sheet with the mapped error")
    fun quickConnectPollingFails() =
        runTest {
            coEvery { authRepository.initiateQuickConnect() } returns
                AppResult.Success(QuickConnectSession(secret = SECRET, code = CODE))
            every { authRepository.observeQuickConnectState(SECRET) } returns
                pollingFlow(QuickConnectState.Failed(AppError.Network()))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startQuickConnect()
            advanceUntilIdle()

            viewModel.uiState.value.quickConnect shouldBe null
            viewModel.uiState.value.error shouldBe AuthErrorMessage.CannotConnect
        }

    @Test
    @DisplayName("cancelling Quick Connect stops the polling before it can approve")
    fun quickConnectCancelled() =
        runTest {
            coEvery { authRepository.initiateQuickConnect() } returns
                AppResult.Success(QuickConnectSession(secret = SECRET, code = CODE))
            every { authRepository.observeQuickConnectState(SECRET) } returns
                pollingFlow(QuickConnectState.Approved)
            coEvery { authRepository.loginWithQuickConnect(SERVER, SECRET) } returns
                AppResult.Success(SESSION)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startQuickConnect()
            viewModel.cancelQuickConnect()
            advanceUntilIdle()

            viewModel.uiState.value.quickConnect shouldBe null
            coVerify(exactly = 0) { authRepository.loginWithQuickConnect(any(), any()) }
        }

    @Test
    @DisplayName("a Quick Connect request the server refuses to open surfaces an error")
    fun quickConnectInitiateFails() =
        runTest {
            coEvery { authRepository.initiateQuickConnect() } returns
                AppResult.Failure(AppError.Server(statusCode = 500))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startQuickConnect()
            advanceUntilIdle()

            viewModel.uiState.value.quickConnect shouldBe null
            viewModel.uiState.value.error shouldBe AuthErrorMessage.ServerFailure(statusCode = 500)
        }

    /**
     * Mimics `AuthRepository.observeQuickConnectState`: one waiting emission per poll interval,
     * then exactly one [terminal] state. `delay` runs on the test scheduler's virtual clock.
     */
    private fun pollingFlow(terminal: QuickConnectState): Flow<QuickConnectState> =
        flow {
            emit(QuickConnectState.WaitingForApproval)
            delay(POLL_INTERVAL_MILLIS)
            emit(QuickConnectState.WaitingForApproval)
            delay(POLL_INTERVAL_MILLIS)
            emit(terminal)
        }

    private companion object {
        const val USER_NAME = "casey"
        const val PASSWORD = "hunter2"
        const val SECRET = "quick-connect-secret"
        const val CODE = "428913"
        const val POLL_INTERVAL_MILLIS = 5_000L
        const val IMAGE_TAG = "a1b2c3d4e5"

        val SERVER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val SERVER =
            ResolvedServer(
                serverId = SERVER_ID,
                name = "Living Room",
                version = "10.11.0",
                address = "https://media.example.com",
            )

        val PUBLIC_USER = PublicUserInfo(id = USER_ID, name = USER_NAME, primaryImageTag = null)

        val USER_WITH_AVATAR = PUBLIC_USER.copy(primaryImageTag = IMAGE_TAG)

        val LOGIN_CONTEXT =
            LoginContext(
                publicUsers = listOf(PUBLIC_USER),
                loginDisclaimer = "Be nice.",
                quickConnectEnabled = true,
            )

        val SESSION =
            AuthenticatedSession(
                serverId = SERVER_ID,
                userId = USER_ID,
                userName = USER_NAME,
                serverName = "Living Room",
                serverVersion = "10.11.0",
                downloadPolicyAllowed = true,
            )
    }
}
