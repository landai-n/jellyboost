package dev.jellyboost.data

import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.connectivity.onlineStateChanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The connection changed — what you are showing came from the other source."
 *
 * `DelegatingJellyfinRepository` picks its source per *call*, which is enough for anything fetched
 * after the change and does nothing for a screen that already fetched: it keeps the answer it got
 * until the user leaves and comes back (STATUS.md, M6 known issues). Screens collect this and re-run
 * whatever load they already have.
 *
 * The signal is a `Flow<Unit>`, and the state a `Boolean`, on purpose. Feature modules depend on
 * `:data`, not on `:core:network`, so exposing `ConnectionState` here would drag connectivity types
 * — and the reason taxonomy, which only the offline banner has any use for — into every ViewModel
 * that wants to know "refresh now" or "is there a server to ask".
 *
 * It fires on **both** edges — reachable again, and no longer reachable — and additionally whenever
 * a probe reconfirms the server after a request had already fallen back to offline data
 * ([ConnectionStateProvider.serverReconfirmed]): that fallback leaves a screen showing downloads-only
 * rows while the state still reads online, so no edge would ever come to correct it (DECISIONS.md,
 * 2026-07-31). It still says nothing about the connection state a screen starts with; see
 * [onlineStateChanges].
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
