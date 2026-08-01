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

/**
 * Width requested for a public user's profile picture, in pixels.
 *
 * The avatar slot is 56dp, so this is ~3x — enough for the xxhdpi tablet the project targets
 * without asking the server to re-encode a full-size portrait for a 56dp circle.
 */
private const val AVATAR_MAX_WIDTH_PX = 168

/**
 * Builds the URL of [user]'s profile picture on the server at [serverAddress], or `null` when
 * there is nothing to load — the server advertises no avatar for them (`primaryImageTag == null`,
 * the common case) or no server is known yet.
 *
 * Kept a pure top-level function rather than a member so the URL shape — which the
 * `PublicUserInfo` KDoc only describes in prose — is directly unit-testable.
 *
 * @param serverAddress base URL of the server; a trailing slash is tolerated.
 */
internal fun publicUserAvatarUrl(
    serverAddress: String?,
    user: PublicUserInfo,
    maxWidth: Int = AVATAR_MAX_WIDTH_PX,
): String? {
    val tag = user.primaryImageTag?.takeIf { it.isNotBlank() } ?: return null
    val base = serverAddress?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    return "$base/Users/${user.id}/Images/Primary?tag=$tag&maxWidth=$maxWidth"
}

/** The Quick Connect sheet, present only while a request is open. */
internal data class QuickConnectUiState(
    /** The short code the user types into an already-authenticated client. */
    val code: String,
    /** False once the code was approved and the token exchange is running. */
    val isWaiting: Boolean = true,
)

/** Everything the login screen renders. */
internal data class LoginUiState(
    val serverName: String = "",
    val serverVersion: String? = null,
    /** Base URL of the server being signed in to; only used to build [avatarUrlFor]. */
    val serverAddress: String? = null,
    /** True until the public users / branding / Quick Connect probe has answered. */
    val isLoadingContext: Boolean = true,
    val publicUsers: List<PublicUserInfo> = emptyList(),
    val loginDisclaimer: String? = null,
    val quickConnectEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    /** True while credentials (or an approved Quick Connect secret) are being exchanged. */
    val isSigningIn: Boolean = false,
    val quickConnect: QuickConnectUiState? = null,
    val error: AuthErrorMessage? = null,
) {
    /** Jellyfin allows blank passwords, so only a username is required. */
    val canSignIn: Boolean get() = username.isNotBlank() && !isSigningIn

    /** Profile picture for [user], or `null` when the initial-letter fallback should be drawn. */
    fun avatarUrlFor(user: PublicUserInfo): String? = publicUserAvatarUrl(serverAddress, user)

    /**
     * Redacts [password] (audit SEC-09): the generated data-class `toString()` would otherwise
     * print it in full the moment this state ever reaches a log line — state-restoration crash
     * reports and `Timber` calls that dump a whole UI state are exactly the paths that do.
     */
    override fun toString(): String =
        "LoginUiState(serverName='$serverName', serverVersion=$serverVersion, " +
            "serverAddress=$serverAddress, " +
            "isLoadingContext=$isLoadingContext, publicUsers=$publicUsers, " +
            "loginDisclaimer=$loginDisclaimer, quickConnectEnabled=$quickConnectEnabled, " +
            "username='$username', password=<redacted>, isSigningIn=$isSigningIn, " +
            "quickConnect=$quickConnect, error=$error)"
}

/** One-shot navigation instructions from the login screen. */
internal sealed interface LoginNavigationEvent {
    /** A session was established; leave the auth flow. */
    data object LoggedIn : LoginNavigationEvent

    /** No pending server (process death mid-flow, or a direct deep link); go back and pick one. */
    data object ServerMissing : LoginNavigationEvent
}

/**
 * Backs the Login screen: login context, password sign-in and Quick Connect
 * (docs/PLAN.md, "Login").
 *
 * The server to authenticate against comes from [PendingServerStore], written by
 * `ServerSetupViewModel` (see DECISIONS.md, 2026-07-28).
 *
 * A successful sign-in is announced twice on purpose: `SessionRepository.sessionState` flips to
 * `LoggedIn` inside `AuthRepository` (which is what drives app-wide reactions such as a future
 * 401-triggered logout), and [navigationEvents] emits [LoginNavigationEvent.LoggedIn] so the
 * NavHost's forward transition stays an explicit, local consequence of this screen's action
 * rather than an implicit side effect of global state.
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

        /** State of the login screen. */
        val uiState: StateFlow<LoginUiState> = mutableUiState.asStateFlow()

        private val navigationChannel = Channel<LoginNavigationEvent>(Channel.BUFFERED)

        /** One-shot navigation events; collect them from the screen composable. */
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

        /** Records what the user typed into the username field. */
        fun onUsernameChange(value: String) {
            mutableUiState.update { it.copy(username = value, error = null) }
        }

        /** Records what the user typed into the password field. */
        fun onPasswordChange(value: String) {
            mutableUiState.update { it.copy(password = value, error = null) }
        }

        /** Pre-fills the username from the public-user row. */
        fun onPublicUserSelected(user: PublicUserInfo) {
            mutableUiState.update { it.copy(username = user.name, error = null) }
        }

        /** Signs in with the username/password currently in state. */
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

        /**
         * Opens a Quick Connect request and follows it to its terminal state.
         *
         * The polling flow is finite, so this job ends on its own; cancelling it (via
         * [cancelQuickConnect] or `onCleared`) is what stops the 5s polling early.
         */
        fun startQuickConnect() {
            val resolved = server ?: return
            if (quickConnectJob?.isActive == true) return

            quickConnectJob =
                viewModelScope.launch {
                    mutableUiState.update { it.copy(error = null) }

                    val initiated = authRepository.initiateQuickConnect()
                    if (initiated is AppResult.Failure) {
                        Timber.w("Could not initiate Quick Connect: %s", initiated.error)
                        mutableUiState.update { it.copy(error = AuthErrorMessage.from(initiated.error)) }
                        return@launch
                    }

                    val session = (initiated as AppResult.Success).value
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

        /** Closes the Quick Connect UI and stops polling. */
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
