package dev.jellyboost.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ServerDiscoveryRepository
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.DiscoveredServer
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
     * Without it the two are indistinguishable: an unreadable credential store used to be wiped and
     * recreated in silence, and the user simply found themselves at server setup
     * (docs/notes/audit-2026-07.md, SEC-03).
     */
    val sessionWasLost: Boolean = false,
) {
    /** The Connect button is only live for a non-blank address outside an in-flight probe. */
    val canConnect: Boolean get() = address.isNotBlank() && !isConnecting
}

/**
 * Backs the ServerSetup screen: local-network discovery plus manual address resolution
 * (docs/PLAN.md, "ServerSetup").
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

        init {
            observeLocalServers()
        }

        /**
         * Records what the user typed into the address field.
         *
         * Ignored while a probe is in flight. The field is no longer disabled during one — a
         * disabled field destroys its accessibility node and drops focus (accessibility audit
         * 2026-08-05, F17) — so the guard moved here, where it is stronger: [connectTo] captured the
         * address it is resolving, and letting the field drift away from it would leave the screen
         * showing one address while reporting the outcome of another.
         */
        fun onAddressChange(value: String) {
            if (mutableUiState.value.isConnecting) return
            mutableUiState.update { it.copy(address = value, error = null) }
        }

        /** Probes whatever is currently in the address field. */
        fun connect() {
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
                    mutableUiState.update { it.copy(address = trimmed, isConnecting = true, error = null) }

                    when (val result = serverDiscoveryRepository.resolveServerAddress(trimmed)) {
                        is AppResult.Success -> {
                            pendingServerStore.set(result.value)
                            mutableUiState.update { it.copy(isConnecting = false, error = null) }
                            navigationChannel.send(Unit)
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
