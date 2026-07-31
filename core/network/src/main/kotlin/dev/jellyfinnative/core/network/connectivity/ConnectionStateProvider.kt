package dev.jellyfinnative.core.network.connectivity

import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.di.ApplicationScope
import dev.jellyfinnative.core.network.model.SessionState
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
 * That ranking is also why [ConnectionState.OFFLINE_SERVER_UNREACHABLE] can be read as "there is a
 * network, and the server is not answering on it".
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
 *
 * Three things beyond that keep a wrong verdict from sticking (DECISIONS.md, 2026-07-31):
 *
 * - A probe requested while the session is still [SessionState.Unknown] is dropped rather than run.
 *   [ServerReachabilityProbe] answers "unreachable" when nobody is signed in, so probing during the
 *   milliseconds before `restoreSession()` publishes anything used to demote the launch optimism and
 *   put every cold start on the offline home.
 * - While the state reads [ConnectionState.OFFLINE_SERVER_UNREACHABLE] the provider re-probes every
 *   [UNREACHABLE_REPROBE_MS] — see [reprobeWhileUnreachable].
 * - A probe that confirms an already-reachable server after a [reportFailure] emits
 *   [serverReconfirmed]. The verdict did not change, so there is no state edge for screens to react
 *   to, yet one of them is showing offline data because its request fell back to Room.
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
        /**
         * Optimistic until proven otherwise: the app starts assuming its server is there, and a
         * probe (kicked off below) demotes it within [ServerReachabilityProbe.PROBE_TIMEOUT_MS] if
         * it is not. Starting pessimistic would show every user an offline banner on launch.
         */
        private val serverReachable = MutableStateFlow(true)

        /** Conflated: a burst of failure reports collapses into a single pending probe. */
        private val probeRequests = Channel<Unit>(Channel.CONFLATED)

        /**
         * Whether a transport failure has been reported since the last completed probe.
         *
         * Set by [reportFailure] and cleared by the probe that answers it, which is what lets that
         * probe tell "the server was fine all along" apart from "somebody fell back to Room and is
         * still showing it".
         */
        private val fallbackReported = AtomicBoolean(false)

        private val reconfirmations =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Ticks when a probe reconfirms a server that the state already called reachable, after a
         * request had reported a transport failure and fallen back to offline data.
         *
         * No state edge accompanies it — the verdict never changed — so this is the only thing that
         * can tell a screen holding that fallback data to load again (`ConnectivityRefresher`).
         */
        val serverReconfirmed: SharedFlow<Unit> = reconfirmations.asSharedFlow()

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
            scope.launch { reprobeWhileUnreachable() }
        }

        /**
         * Asks for a re-probe because a request just failed at the transport level.
         *
         * Called by `DelegatingJellyfinRepository` (docs/PLAN.md, "Data layer"). Cheap and
         * non-suspending: it only drops a token in the channel.
         */
        fun reportFailure() {
            Timber.d("Transport failure reported; queueing a reachability probe")
            fallbackReported.set(true)
            probeRequests.trySend(Unit)
        }

        /** Asks for a re-probe on app resume, or when the user taps *Retry* on the offline banner. */
        fun refresh() {
            probeRequests.trySend(Unit)
        }

        /**
         * The single probe consumer. It must outlive anything one probe can do to it.
         *
         * This loop is the app's only offline detector, and it runs for the lifetime of the
         * process: if one iteration throws, the loop dies and the app is left permanently on
         * whatever verdict it happened to be holding — no further probe, no recovery, no banner.
         * [ServerReachabilityProbe] is careful, but "the probe never throws" is not a property this
         * loop should be depending on, so a failed iteration costs its own verdict and nothing more.
         * The last verdict is kept deliberately: a probe that threw learnt nothing, and inventing
         * "unreachable" from it would show an offline banner on the strength of a bug — which is
         * also why such an iteration leaves [fallbackReported] set for the next real probe to answer.
         */
        private suspend fun consumeProbeRequests() {
            while (true) {
                probeRequests.receive()
                if (sessionStateHolder.state.value == SessionState.Unknown) {
                    // Probing with no session answers "unreachable" whatever the server is doing.
                    // The conflated channel bounds the requests this drops, and `probeOnSessionChange`
                    // asks again the moment the restore lands — including when it lands between this
                    // check and the next `receive`.
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
                // The pause is the debounce: requests that arrive while it runs are conflated into
                // the single token waiting in the channel.
                delay(PROBE_DEBOUNCE_MS)
            }
        }

        /**
         * Consumes the pending [fallbackReported] flag and ticks [serverReconfirmed] if this probe
         * confirmed a server the state already considered reachable.
         *
         * The flag is cleared on every completed probe, whatever the verdict: any verdict that
         * *did* change produces a state edge, and screens already refresh on those.
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
         * Keeps asking while the answer is [ConnectionState.OFFLINE_SERVER_UNREACHABLE].
         *
         * Nothing else would ask: an offline state routes every repository call straight to Room, so
         * no request can fail at the transport level any more and [reportFailure] never fires again,
         * and `ConnectivityMonitor.hasNetwork` is distinct-until-changed. Without this loop a wrong
         * "unreachable" verdict lasts the whole foreground session and only app resume or a *Retry*
         * tap can end it.
         *
         * `collectLatest` cancels the loop on any state change, and the loop itself is needed
         * because a re-probe that fails writes `false` over `false` — no state emission, so nothing
         * would restart a one-shot delay. There is no need to check for a network first: the
         * ranking above means this state can only be reached with one.
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

            /**
             * How often to re-ask while the server looks unreachable, in milliseconds.
             *
             * The only recovery path there is inside a foreground session (see
             * [reprobeWhileUnreachable]), so it has to be short enough that a user who watched a
             * transient failure gets their library back without hunting for *Retry*, and long
             * enough that a genuinely dead server costs one unauthenticated `getPublicSystemInfo`
             * every quarter minute — which is roughly what a heartbeat would cost anyway.
             */
            const val UNREACHABLE_REPROBE_MS = 15_000L
        }
    }
