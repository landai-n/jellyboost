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

internal data class ServerSetupUiState(
    val address: String = "",
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isDiscovering: Boolean = true,
    val isConnecting: Boolean = false,
    val error: AuthErrorMessage? = null,
    /**
     * Distinguishes a lost session from a first sign-in: an unreadable credential store is wiped and
     * recreated in silence, so without this the user simply finds themselves back at server setup.
     */
    val sessionWasLost: Boolean = false,
    /**
     * Set instead of navigating: the flow stops here and the next press of Connect goes through.
     * Non-null only between the resolve that raised it and the press that acknowledges it.
     */
    val cleartextWarningHost: String? = null,
) {
    val canConnect: Boolean get() = address.isNotBlank() && !isConnecting
}

/**
 * Fetching the login context deliberately stays on the Login screen, so a slow branding or
 * Quick-Connect probe cannot make the Connect button look stuck.
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
                // Consumed rather than observed, so signing out and coming back does not replay it. The splash
                // holds until session restore has answered, so the answer is settled by the time this exists.
                ServerSetupUiState(sessionWasLost = sessionRepository.consumeInvoluntarySignOut()),
            )

        val uiState: StateFlow<ServerSetupUiState> = mutableUiState.asStateFlow()

        private val navigationChannel = Channel<Unit>(Channel.BUFFERED)

        val navigateToLogin: Flow<Unit> = navigationChannel.receiveAsFlow()

        private var connectJob: Job? = null

        /** Kept so acknowledging the warning costs no second round-trip to a server that already answered. */
        private var warnedServer: ResolvedServer? = null

        init {
            observeLocalServers()
        }

        /**
         * Ignored while a probe is in flight: the field stays enabled for accessibility, and letting
         * it drift from the address [connectTo] captured would report one address under another.
         */
        fun onAddressChange(value: String) {
            if (mutableUiState.value.isConnecting) return
            // A new address is a new question: the standing warning must not be acknowledgeable by a
            // Connect aimed somewhere else.
            warnedServer = null
            mutableUiState.update { it.copy(address = value, error = null, cleartextWarningHost = null) }
        }

        /** A press with a cleartext warning standing is the acknowledgement of it, not a new probe. */
        fun connect() {
            val acknowledged = warnedServer
            if (acknowledged != null && mutableUiState.value.cleartextWarningHost != null) {
                viewModelScope.launch { proceedTo(acknowledged) }
                return
            }
            connectTo(mutableUiState.value.address)
        }

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
                            // The *resolved* address, not what was typed: a bare hostname can resolve to either scheme.
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

        private suspend fun proceedTo(server: ResolvedServer) {
            warnedServer = null
            pendingServerStore.set(server)
            mutableUiState.update { it.copy(isConnecting = false, error = null, cleartextWarningHost = null) }
            navigationChannel.send(Unit)
        }

        /** Dedupes: servers broadcast several times per discovery window. */
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
