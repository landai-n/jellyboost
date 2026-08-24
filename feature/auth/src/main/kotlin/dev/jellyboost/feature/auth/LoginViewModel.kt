package dev.jellyboost.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.AuthRepository
import dev.jellyboost.core.network.model.PublicUserInfo
import dev.jellyboost.core.network.model.QuickConnectState
import dev.jellyboost.core.network.model.ResolvedServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** ~3x the 56dp avatar slot: enough for xxhdpi without asking the server to re-encode a portrait. */
private const val AVATAR_MAX_WIDTH_PX = 168

internal fun publicUserAvatarUrl(
    serverAddress: String?,
    user: PublicUserInfo,
    maxWidth: Int = AVATAR_MAX_WIDTH_PX,
): String? {
    val tag = user.primaryImageTag?.takeIf { it.isNotBlank() } ?: return null
    val base = serverAddress?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    return "$base/Users/${user.id}/Images/Primary?tag=$tag&maxWidth=$maxWidth"
}

internal data class QuickConnectUiState(
    val code: String,
    /** False once the code was approved and the token exchange is running. */
    val isWaiting: Boolean = true,
)

internal data class LoginUiState(
    val serverName: String = "",
    val serverVersion: String? = null,
    val serverAddress: String? = null,
    val isLoadingContext: Boolean = true,
    val publicUsers: List<PublicUserInfo> = emptyList(),
    val loginDisclaimer: String? = null,
    val quickConnectEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val isSigningIn: Boolean = false,
    val quickConnect: QuickConnectUiState? = null,
    val error: AuthErrorMessage? = null,
) {
    /** Jellyfin allows blank passwords, so only a username is required. */
    val canSignIn: Boolean get() = username.isNotBlank() && !isSigningIn

    fun avatarUrlFor(user: PublicUserInfo): String? = publicUserAvatarUrl(serverAddress, user)

    /**
     * Redacts [password]: the generated `toString()` would print it in full the moment this state
     * reaches a log line, which crash reports and whole-state `Timber` calls do.
     */
    override fun toString(): String =
        "LoginUiState(serverName='$serverName', serverVersion=$serverVersion, " +
            "serverAddress=$serverAddress, " +
            "isLoadingContext=$isLoadingContext, publicUsers=$publicUsers, " +
            "loginDisclaimer=$loginDisclaimer, quickConnectEnabled=$quickConnectEnabled, " +
            "username='$username', password=<redacted>, isSigningIn=$isSigningIn, " +
            "quickConnect=$quickConnect, error=$error)"
}

internal sealed interface LoginNavigationEvent {
    data object LoggedIn : LoginNavigationEvent

    /** Process death mid-flow, or a direct deep link: go back and pick a server. */
    data object ServerMissing : LoginNavigationEvent
}

/**
 * A successful sign-in is announced twice on purpose: `SessionRepository.sessionState` flips inside
 * `AuthRepository` (driving app-wide reactions such as a 401 logout), and [navigationEvents] emits
 * so the NavHost transition stays a local consequence rather than a global side effect.
 */
@HiltViewModel
internal class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val pendingServerStore: PendingServerStore,
    ) : ViewModel() {
        private val server: ResolvedServer? = pendingServerStore.server

        private val mutableUiState =
            MutableStateFlow(
                LoginUiState(
                    serverName = server?.name.orEmpty(),
                    serverVersion = server?.version,
                    serverAddress = server?.address,
                    isLoadingContext = server != null,
                ),
            )

        val uiState: StateFlow<LoginUiState> = mutableUiState.asStateFlow()

        private val navigationChannel = Channel<LoginNavigationEvent>(Channel.BUFFERED)

        val navigationEvents: Flow<LoginNavigationEvent> = navigationChannel.receiveAsFlow()

        private var quickConnectJob: Job? = null
        private var signInJob: Job? = null

        init {
            val resolved = server
            if (resolved == null) {
                Timber.w("Login opened without a resolved server; returning to server setup")
                viewModelScope.launch { navigationChannel.send(LoginNavigationEvent.ServerMissing) }
            } else {
                loadLoginContext(resolved)
            }
        }

        /**
         * Ignored while a sign-in is in flight, which is what lets both credential fields stay
         * *enabled* through the exchange: a disabled field destroys its accessibility node and
         * throws focus to the top of the screen.
         */
        fun onUsernameChange(value: String) {
            if (mutableUiState.value.isSigningIn) return
            mutableUiState.update { it.copy(username = value, error = null) }
        }

        /** Inert mid-request, see [onUsernameChange]. */
        fun onPasswordChange(value: String) {
            if (mutableUiState.value.isSigningIn) return
            mutableUiState.update { it.copy(password = value, error = null) }
        }

        /** Inert mid-request, see [onUsernameChange]. */
        fun onPublicUserSelected(user: PublicUserInfo) {
            if (mutableUiState.value.isSigningIn) return
            mutableUiState.update { it.copy(username = user.name, error = null) }
        }

        fun signIn() {
            val resolved = server ?: return
            val state = mutableUiState.value
            if (!state.canSignIn || signInJob?.isActive == true) return

            signInJob =
                viewModelScope.launch {
                    mutableUiState.update { it.copy(isSigningIn = true, error = null) }
                    val result =
                        authRepository.loginWithPassword(
                            server = resolved,
                            username = state.username.trim(),
                            password = state.password,
                        )
                    handleAuthenticationResult(result)
                }
        }

        /** The polling flow is finite; cancelling the job is what stops the 5s polling early. */
        fun startQuickConnect() {
            val resolved = server ?: return
            if (quickConnectJob?.isActive == true) return

            quickConnectJob =
                viewModelScope.launch {
                    mutableUiState.update { it.copy(error = null) }

                    // Exhaustive rather than an unchecked cast: a third `AppResult` variant would otherwise turn a
                    // compile error into a sign-in-path crash.
                    val session =
                        when (val initiated = authRepository.initiateQuickConnect()) {
                            is AppResult.Failure -> {
                                Timber.w("Could not initiate Quick Connect: %s", initiated.error)
                                mutableUiState.update { it.copy(error = AuthErrorMessage.from(initiated.error)) }
                                return@launch
                            }

                            is AppResult.Success -> initiated.value
                        }
                    mutableUiState.update { it.copy(quickConnect = QuickConnectUiState(code = session.code)) }

                    authRepository.observeQuickConnectState(session.secret).collect { state ->
                        when (state) {
                            QuickConnectState.WaitingForApproval -> Unit

                            QuickConnectState.Approved -> {
                                mutableUiState.update {
                                    it.copy(
                                        quickConnect = it.quickConnect?.copy(isWaiting = false),
                                        isSigningIn = true,
                                    )
                                }
                                handleAuthenticationResult(
                                    authRepository.loginWithQuickConnect(resolved, session.secret),
                                )
                            }

                            QuickConnectState.Expired ->
                                mutableUiState.update {
                                    it.copy(
                                        quickConnect = null,
                                        error = AuthErrorMessage.QuickConnectExpired,
                                    )
                                }

                            is QuickConnectState.Failed ->
                                mutableUiState.update {
                                    it.copy(
                                        quickConnect = null,
                                        error = AuthErrorMessage.from(state.error),
                                    )
                                }
                        }
                    }
                }
        }

        fun cancelQuickConnect() {
            quickConnectJob?.cancel()
            quickConnectJob = null
            mutableUiState.update { it.copy(quickConnect = null, isSigningIn = false) }
        }

        /** Drops the pending server so backing out of Login restarts the flow cleanly. */
        fun onLeavingLogin() {
            pendingServerStore.clear()
        }

        private fun loadLoginContext(resolved: ResolvedServer) {
            viewModelScope.launch {
                when (val result = authRepository.fetchLoginContext(resolved)) {
                    is AppResult.Success ->
                        mutableUiState.update {
                            it.copy(
                                isLoadingContext = false,
                                publicUsers = result.value.publicUsers,
                                loginDisclaimer = result.value.loginDisclaimer,
                                quickConnectEnabled = result.value.quickConnectEnabled,
                            )
                        }

                    is AppResult.Failure -> {
                        Timber.w("Could not load the login context: %s", result.error)
                        mutableUiState.update {
                            it.copy(isLoadingContext = false, error = AuthErrorMessage.from(result.error))
                        }
                    }
                }
            }
        }

        private suspend fun handleAuthenticationResult(result: AppResult<*>) {
            when (result) {
                is AppResult.Success -> {
                    pendingServerStore.clear()
                    mutableUiState.update {
                        it.copy(isSigningIn = false, password = "", quickConnect = null, error = null)
                    }
                    navigationChannel.send(LoginNavigationEvent.LoggedIn)
                }

                is AppResult.Failure -> {
                    Timber.w("Sign-in failed: %s", result.error)
                    mutableUiState.update {
                        it.copy(
                            isSigningIn = false,
                            quickConnect = null,
                            error = AuthErrorMessage.from(result.error),
                        )
                    }
                }
            }
        }
    }
