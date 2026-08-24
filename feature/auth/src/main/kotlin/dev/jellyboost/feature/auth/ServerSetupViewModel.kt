package dev.jellyboost.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ServerDiscoveryRepository
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.DiscoveredServer
import dev.jellyboost.core.network.model.ResolvedServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Everything the server-setup screen renders. */
internal data class ServerSetupUiState(
    /** Contents of the manual address field. */
    val address: String = "",
    /** Servers that announced themselves on the local network, in arrival order. */
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    /** True while the UDP discovery flow is still running. */
    val isDiscovering: Boolean = true,
    /** True while an address is being probed. */
    val isConnecting: Boolean = false,
    val error: AuthErrorMessage? = null,
    /**
     * True when this screen is being shown because a stored session was lost, rather than because
     * the user has never signed in.
     *
     * Without it the two are indistinguishable: an unreadable credential store is wiped and
     * recreated in silence, and the user simply finds themselves at server setup.
     */
    val sessionWasLost: Boolean = false,
    /**
     * Host of a server that resolved to a plain `http://` address outside the local network, or
     * `null` when there is nothing to warn about.
     *
     * Set instead of navigating: the flow stops here, says what is about to happen to the access
     * token, and the next press of Connect goes through. Non-null only between the resolve that
     * raised it and the press that acknowledges it.
     */
    val cleartextWarningHost: String? = null,
) {
    /** The Connect button is only live for a non-blank address outside an in-flight probe. */
    val canConnect: Boolean get() = address.isNotBlank() && !isConnecting
}

/**
 * Backs the ServerSetup screen: local-network discovery plus manual address resolution.
 *
 * Resolving an address is where this screen's job ends — the resolved server is handed to the
 * Login screen through [PendingServerStore] and a one-shot [navigateToLogin] event. Fetching the
 * login context deliberately stays on the Login screen so a slow branding/Quick-Connect probe
 * cannot make the Connect button look stuck.
 */
@HiltViewModel
internal class ServerSetupViewModel
    @Inject
    constructor(
        private val serverDiscoveryRepository: ServerDiscoveryRepository,
        private val pendingServerStore: PendingServerStore,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val mutableUiState =
            MutableStateFlow(
                // Read once, here: the splash is held until the session restore has answered, so by
                // the time this screen exists the answer is settled. It is consumed rather than
                // observed so that signing out and coming back does not replay it.
                ServerSetupUiState(sessionWasLost = sessionRepository.consumeInvoluntarySignOut()),
            )

        /** State of the server-setup screen. */
        val uiState: StateFlow<ServerSetupUiState> = mutableUiState.asStateFlow()

        private val navigationChannel = Channel<Unit>(Channel.BUFFERED)

        /** Emits once per successfully resolved server; collect it to navigate to Login. */
        val navigateToLogin: Flow<Unit> = navigationChannel.receiveAsFlow()

        private var connectJob: Job? = null

        /**
         * The server behind [ServerSetupUiState.cleartextWarningHost], kept so that acknowledging
         * the warning costs no second round-trip to a server that has already answered.
         */
        private var warnedServer: ResolvedServer? = null

        init {
            observeLocalServers()
        }

        /**
         * Records what the user typed into the address field.
         *
         * Ignored while a probe is in flight. The field stays enabled throughout — a disabled field
         * destroys its accessibility node and drops focus — so this guard is what keeps it from
         * being edited mid-probe: [connectTo] captured the address it is resolving, and letting the
         * field drift away from it would leave the screen showing one address while reporting the
         * outcome of another.
         */
        fun onAddressChange(value: String) {
            if (mutableUiState.value.isConnecting) return
            // A new address is a new question: the standing cleartext warning was about the old one
            // and must not be acknowledgeable by a Connect aimed somewhere else.
            warnedServer = null
            mutableUiState.update { it.copy(address = value, error = null, cleartextWarningHost = null) }
        }

        /**
         * Probes whatever is currently in the address field — or, when a cleartext warning is
         * standing for the server this field already resolved to, takes the press as the
         * acknowledgement of it and goes on to Login.
         */
        fun connect() {
            val acknowledged = warnedServer
            if (acknowledged != null && mutableUiState.value.cleartextWarningHost != null) {
                viewModelScope.launch { proceedTo(acknowledged) }
                return
            }
            connectTo(mutableUiState.value.address)
        }

        /**
         * Probes [input] — a discovered server's address or free text the user typed — and, when
         * it turns out to be a usable Jellyfin server, moves the flow on to Login.
         */
        fun connectTo(input: String) {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || connectJob?.isActive == true) return

            connectJob =
                viewModelScope.launch {
                    warnedServer = null
                    mutableUiState.update {
                        it.copy(
                            address = trimmed,
                            isConnecting = true,
                            error = null,
                            cleartextWarningHost = null,
                        )
                    }

                    when (val result = serverDiscoveryRepository.resolveServerAddress(trimmed)) {
                        is AppResult.Success -> {
                            val server = result.value
                            // The *resolved* address, not what was typed: a bare hostname can
                            // resolve to https just as easily as to http, and only the answer says
                            // which one this app is actually going to use.
                            val warnAbout =
                                hostOf(server.address).takeIf { isCleartextPublicAddress(server.address) }
                            if (warnAbout == null) {
                                proceedTo(server)
                            } else {
                                warnedServer = server
                                mutableUiState.update {
                                    it.copy(isConnecting = false, error = null, cleartextWarningHost = warnAbout)
                                }
                            }
                        }

                        is AppResult.Failure -> {
                            Timber.w("Could not resolve '%s': %s", trimmed, result.error)
                            mutableUiState.update {
                                it.copy(
                                    isConnecting = false,
                                    error = AuthErrorMessage.from(result.error),
                                )
                            }
                        }
                    }
                }
        }

        /** Hands [server] to the Login screen and moves the flow on. */
        private suspend fun proceedTo(server: ResolvedServer) {
            warnedServer = null
            pendingServerStore.set(server)
            mutableUiState.update { it.copy(isConnecting = false, error = null, cleartextWarningHost = null) }
            navigationChannel.send(Unit)
        }

        /**
         * Collects the (finite) discovery flow once, accumulating announcements and dropping
         * repeats — servers broadcast several times per discovery window.
         */
        private fun observeLocalServers() {
            viewModelScope.launch {
                serverDiscoveryRepository
                    .discoverLocalServers()
                    .catch { throwable -> Timber.w(throwable, "Local server discovery failed") }
                    .onCompletion { mutableUiState.update { it.copy(isDiscovering = false) } }
                    .collect { server ->
                        mutableUiState.update { state ->
                            if (state.discoveredServers.any { it.id == server.id }) {
                                state
                            } else {
                                state.copy(discoveredServers = state.discoveredServers + server)
                            }
                        }
                    }
            }
        }
    }
