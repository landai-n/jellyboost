package dev.jellyboost.data

import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.connectivity.onlineStateChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The connection changed — what you are showing came from the other source."
 *
 * `DelegatingJellyfinRepository` picks its source per *call*, which is enough for anything fetched
 * after the change and does nothing for a screen that already fetched: it keeps the answer it got
 * until the user leaves and comes back. Screens collect this and re-run whatever load they already
 * have.
 *
 * The signal is a `Flow<Unit>`, and the state a `Boolean`, on purpose. Feature modules depend on
 * `:data`, not on `:core:network`, so exposing `ConnectionState` here would drag connectivity types
 * — and the reason taxonomy, which only the offline banner has any use for — into every ViewModel
 * that wants to know "refresh now" or "is there a server to ask".
 *
 * It fires on **both** edges — reachable again, and no longer reachable — and additionally whenever
 * a probe reconfirms the server after a request had already fallen back to offline data
 * ([ConnectionStateProvider.serverReconfirmed]): that fallback leaves a screen showing downloads-only
 * rows while the state still reads online, so no edge would ever come to correct it. It still says
 * nothing about the connection state a screen starts with; see [onlineStateChanges].
 */
@Singleton
class ConnectivityRefresher
    @Inject
    constructor(
        private val connectionStateProvider: ConnectionStateProvider,
    ) {
        /**
         * Fires once per change of online-ness, in either direction, and once per reconfirmed
         * server. Never completes.
         */
        val connectivityChanged: Flow<Unit> =
            merge(
                connectionStateProvider.state.onlineStateChanges().map { },
                connectionStateProvider.serverReconfirmed,
            )

        /**
         * Whether the app is online *right now*.
         *
         * A point read rather than a flow: the callers are one-shot decisions taken inside a
         * coroutine that is about to fetch — "is this request worth making at all" — for which
         * collecting a state flow would be ceremony around `.value`. Screens that need to *react*
         * to the connection changing collect [connectivityChanged] instead.
         */
        val isOnline: Boolean
            get() = connectionStateProvider.state.value.isOnline
    }

/**
 * Re-runs [reload] on every connection change, for as long as [scope] lives.
 *
 * Consolidates the reload call every screen's ViewModel would otherwise repeat, along with the
 * argument for doing it this way:
 *
 * **Both directions matter, and that is the point of collecting this rather than an "online again"
 * signal.** A screen opened in airplane mode keeps showing downloaded media after the server comes
 * back; one opened online keeps showing *its* rows — links to media the app can no longer play —
 * after the user pins offline mode or walks out of range. The reload is the same call either way,
 * because `DelegatingJellyfinRepository` picks the source per request: the screen does not know or
 * care which side of the change it is on.
 *
 * Reloading is also the only correct response to a *reconfirmation*
 * ([ConnectivityRefresher.connectivityChanged] fires on those too): a request that already fell
 * back to offline data left the screen showing downloads-only rows while the state still read
 * online, so no edge is coming to correct it.
 *
 * **An extension rather than a member, on purpose.** Every ViewModel test in the tree fakes
 * `ConnectivityRefresher` with a MockK stub of `connectivityChanged`. A member function is virtual
 * and would have to be stubbed *as well*, in fifteen-odd test classes, to say the one thing it
 * already says here; an extension is resolved statically, so a mocked refresher runs this real body
 * over its stubbed flow and every existing test keeps testing what it was written to test.
 *
 * @param scope the ViewModel's `viewModelScope`; the collection ends when it is cancelled.
 * @param onlyIf checked on each change, immediately before [reload]. The default reloads every
 *   time; a screen passes one when a reload would be wasted work rather than a correction —
 *   nothing has been fetched yet, or a fetch is already in flight.
 * @param reload what to re-run. Not `suspend`: every caller's is a plain function that launches its
 *   own load, and keeping it that way means this helper never becomes the thing that serialises two
 *   of them.
 */
fun ConnectivityRefresher.reloadOnChange(
    scope: CoroutineScope,
    onlyIf: () -> Boolean = { true },
    reload: () -> Unit,
): Job =
    scope.launch {
        connectivityChanged.collect { if (onlyIf()) reload() }
    }
