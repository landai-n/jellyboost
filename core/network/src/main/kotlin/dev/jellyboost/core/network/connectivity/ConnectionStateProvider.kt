package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single source of truth for online/offline. There is deliberately **no separate offline app
 * mode**: this state drives repository delegation and the one app-wide `OfflineBanner`, nothing else.
 */
@Singleton
class ConnectionStateProvider
    @Inject
    internal constructor(
        connectivityMonitor: ConnectivityMonitor,
        private val sessionStateHolder: SessionStateHolder,
        private val probe: ServerReachabilityProbe,
        appPreferences: AppPreferences,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** Optimistic until a probe says otherwise; starting pessimistic shows every user a banner on launch. */
        private val serverReachable = MutableStateFlow(true)

        private val probeRequests = Channel<Unit>(Channel.CONFLATED)

        /**
         * Set by [reportFailure], cleared by the probe that answers it — what lets that probe tell "the
         * server was fine all along" apart from "somebody fell back to Room and is still showing it".
         */
        private val fallbackReported = AtomicBoolean(false)

        private val reconfirmations =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * No state edge accompanies a reconfirmation — the verdict never changed — so this is the only thing
         * that can tell a screen holding fallback data to load again (`ConnectivityRefresher`).
         */
        val serverReconfirmed: SharedFlow<Unit> = reconfirmations.asSharedFlow()

        val state: StateFlow<ConnectionState> =
            combine(
                connectivityMonitor.hasNetwork,
                serverReachable,
                appPreferences.forceOffline,
            ) { hasNetwork, reachable, forced ->
                when {
                    forced -> ConnectionState.OFFLINE_FORCED
                    !hasNetwork -> ConnectionState.OFFLINE_NO_NETWORK
                    !reachable -> ConnectionState.OFFLINE_SERVER_UNREACHABLE
                    else -> ConnectionState.ONLINE
                }
            }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.ONLINE)

        init {
            scope.launch { consumeProbeRequests() }
            scope.launch { probeOnNetworkChange(connectivityMonitor) }
            scope.launch { probeOnSessionChange(sessionStateHolder) }
            scope.launch { reprobeWhileUnreachable() }
        }

        fun reportFailure() {
            Timber.d("Transport failure reported; queueing a reachability probe")
            fallbackReported.set(true)
            probeRequests.trySend(Unit)
        }

        fun refresh() {
            probeRequests.trySend(Unit)
        }

        /**
         * The app's only offline detector, and it must outlive anything one probe can do to it: if an
         * iteration throws, the loop dies and the app is stuck on its current verdict with no recovery. A
         * failed iteration keeps the last verdict — a probe that threw learnt nothing — and leaves
         * [fallbackReported] set for the next real probe.
         */
        private suspend fun consumeProbeRequests() {
            while (true) {
                probeRequests.receive()
                if (sessionStateHolder.state.value == SessionState.Unknown) {
                    // Probing with no session answers "unreachable" whatever the server is doing;
                    // `probeOnSessionChange` asks again the moment the restore lands.
                    Timber.d("Probe requested before session restore; keeping the launch optimism")
                    continue
                }
                try {
                    val wasReachable = serverReachable.value
                    val reachable = probe.isServerReachable()
                    serverReachable.value = reachable
                    announceReconfirmation(reachable = reachable, wasReachable = wasReachable)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Throwable,
                ) {
                    Timber.w(error, "A reachability probe failed unexpectedly; keeping the last verdict")
                }
                delay(PROBE_DEBOUNCE_MS)
            }
        }

        /**
         * The flag is cleared on every completed probe whatever the verdict: a verdict that *did* change
         * produces a state edge, and screens already refresh on those.
         */
        private fun announceReconfirmation(
            reachable: Boolean,
            wasReachable: Boolean,
        ) {
            val hadFallback = fallbackReported.getAndSet(false)
            if (reachable && wasReachable && hadFallback) {
                Timber.d("Server reconfirmed after a fallback; asking the screens to load again")
                reconfirmations.tryEmit(Unit)
            }
        }

        /**
         * Nothing else would ask: an offline state routes every call to Room, so no request can fail at the
         * transport level and [reportFailure] never fires again. The inner loop is needed because a re-probe
         * that fails writes `false` over `false` — no emission, so nothing would restart a one-shot delay.
         */
        private suspend fun reprobeWhileUnreachable() {
            state.collectLatest { current ->
                while (current == ConnectionState.OFFLINE_SERVER_UNREACHABLE) {
                    delay(UNREACHABLE_REPROBE_MS)
                    Timber.d("Server still unreachable; re-probing")
                    refresh()
                }
            }
        }

        private suspend fun probeOnNetworkChange(connectivityMonitor: ConnectivityMonitor) {
            connectivityMonitor.hasNetwork.collect { hasNetwork ->
                if (hasNetwork) {
                    refresh()
                }
            }
        }

        /**
         * [ServerReachabilityProbe] derives the addresses it tries from the current session, so a verdict
         * reached under one session says nothing about the next. Without this, the first sign-in after a
         * fresh install stayed offline for the whole app run: the launch probe ran before there was a
         * session, answered "no server to probe", and nothing re-asked.
         */
        private suspend fun probeOnSessionChange(sessionStateHolder: SessionStateHolder) {
            sessionStateHolder.state
                // Launch's `Unknown` is not a session change — the network-available probe covers start-up.
                .filter { it != SessionState.Unknown }
                .map(::sessionIdentity)
                .distinctUntilChanged()
                .collect {
                    Timber.d("Session changed; queueing a reachability probe")
                    refresh()
                }
        }

        private fun sessionIdentity(session: SessionState): Pair<UUID, UUID>? =
            (session as? SessionState.LoggedIn)?.let { it.serverId to it.userId }

        companion object {
            /** Long enough to swallow a screenful of parallel failures, short enough not to ignore a double *Retry*. */
            const val PROBE_DEBOUNCE_MS = 2_000L

            /**
             * The only recovery path inside a foreground session, so: short enough that a transient failure
             * does not send the user hunting for *Retry*, long enough that a dead server costs one
             * unauthenticated `getPublicSystemInfo` every quarter minute.
             */
            const val UNREACHABLE_REPROBE_MS = 15_000L
        }
    }
