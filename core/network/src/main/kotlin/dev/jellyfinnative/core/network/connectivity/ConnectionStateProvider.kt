package dev.jellyfinnative.core.network.connectivity

import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.di.ApplicationScope
import dev.jellyfinnative.core.network.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single source of truth for online/offline (docs/PLAN.md, "Connectivity").
 *
 * Three inputs, one answer:
 *
 * | input | source | produces |
 * |---|---|---|
 * | user pinned offline mode | `AppPreferences.forceOffline` | [ConnectionState.OFFLINE_FORCED] |
 * | no usable network | [ConnectivityMonitor] | [ConnectionState.OFFLINE_NO_NETWORK] |
 * | network up, server silent | [ServerReachabilityProbe] | [ConnectionState.OFFLINE_SERVER_UNREACHABLE] |
 *
 * The order matters: a user who asked for offline mode gets it regardless of the network, and
 * "no network" outranks "server unreachable" because it is the more specific thing to tell them.
 *
 * There is deliberately **no separate offline app mode**: this state drives repository delegation
 * and the one app-wide `OfflineBanner`, and nothing else changes.
 *
 * ### Probing
 * Probes are requested, never run inline. A conflated channel feeds a single consumer that runs one
 * probe at a time and then waits [PROBE_DEBOUNCE_MS] — so a screenful of ViewModels all reporting
 * the same failed request produces exactly one `getPublicSystemInfo`, not twelve.
 *
 * A probe is requested on a usable network, on a reported transport failure, on app resume or a
 * *Retry* tap, and **on every change of session** — see [probeOnSessionChange].
 */
@Singleton
class ConnectionStateProvider
    @Inject
    internal constructor(
        connectivityMonitor: ConnectivityMonitor,
        sessionStateHolder: SessionStateHolder,
        private val probe: ServerReachabilityProbe,
        appPreferences: AppPreferences,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * Optimistic until proven otherwise: the app starts assuming its server is there, and a
         * probe (kicked off below) demotes it within [ServerReachabilityProbe.PROBE_TIMEOUT_MS] if
         * it is not. Starting pessimistic would show every user an offline banner on launch.
         */
        private val serverReachable = MutableStateFlow(true)

        /** Conflated: a burst of failure reports collapses into a single pending probe. */
        private val probeRequests = Channel<Unit>(Channel.CONFLATED)

        /** The current connection state. Never completes; safe to collect for the app's lifetime. */
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
        }

        /**
         * Asks for a re-probe because a request just failed at the transport level.
         *
         * Called by `DelegatingJellyfinRepository` (docs/PLAN.md, "Data layer"). Cheap and
         * non-suspending: it only drops a token in the channel.
         */
        fun reportFailure() {
            Timber.d("Transport failure reported; queueing a reachability probe")
            probeRequests.trySend(Unit)
        }

        /** Asks for a re-probe on app resume, or when the user taps *Retry* on the offline banner. */
        fun refresh() {
            probeRequests.trySend(Unit)
        }

        private suspend fun consumeProbeRequests() {
            while (true) {
                probeRequests.receive()
                serverReachable.value = probe.isServerReachable()
                // The pause is the debounce: requests that arrive while it runs are conflated into
                // the single token waiting in the channel.
                delay(PROBE_DEBOUNCE_MS)
            }
        }

        private suspend fun probeOnNetworkChange(connectivityMonitor: ConnectivityMonitor) {
            connectivityMonitor.hasNetwork.collect { hasNetwork ->
                if (hasNetwork) {
                    // A new network is a new chance for the server — and possibly at a different
                    // address, which is what makes the probe rotate its candidates.
                    refresh()
                }
            }
        }

        /**
         * Re-probes whenever *who we are signed in as* changes.
         *
         * [ServerReachabilityProbe] derives the addresses it tries from the current session, so a
         * verdict reached under one session says nothing about the next one. Without this, the very
         * first sign-in after a fresh install stayed offline for the rest of the app run: the launch
         * probe ran before there was any session, correctly answered "no server to probe", and
         * nothing re-asked once the user signed in. The offline→online edge this produces is wanted
         * — it is what makes the screens fetch (`ConnectivityRefresher`).
         *
         * Signing out re-probes too, so the state reflects "no server" rather than the last
         * session's verdict.
         *
         * Session changes are rare and a probe never writes one back, so there is no loop; probes
         * are still spaced by [PROBE_DEBOUNCE_MS], and mapping to the identity means a session
         * re-published unchanged (or with only a refreshed server version) costs nothing.
         */
        private suspend fun probeOnSessionChange(sessionStateHolder: SessionStateHolder) {
            sessionStateHolder.state
                // Launch's `Unknown` is not a session change — the app has not decided yet, and the
                // network-available probe already covers start-up.
                .filter { it != SessionState.Unknown }
                .map(::sessionIdentity)
                .distinctUntilChanged()
                .collect {
                    Timber.d("Session changed; queueing a reachability probe")
                    refresh()
                }
        }

        /** Who the probe would be probing for: `null` once signed out. */
        private fun sessionIdentity(session: SessionState): Pair<UUID, UUID>? =
            (session as? SessionState.LoggedIn)?.let { it.serverId to it.userId }

        companion object {
            /**
             * Minimum spacing between two probes, in milliseconds.
             *
             * Long enough to swallow the burst of failures a screenful of parallel requests
             * produces, short enough that a user tapping *Retry* twice is not ignored.
             */
            const val PROBE_DEBOUNCE_MS = 2_000L
        }
    }
