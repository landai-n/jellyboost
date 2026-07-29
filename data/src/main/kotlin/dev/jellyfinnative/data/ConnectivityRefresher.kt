package dev.jellyfinnative.data

import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.connectivity.onlineStateChanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * The type is a `Flow<Unit>` and nothing else on purpose. Feature modules depend on `:data`, not on
 * `:core:network`, so exposing `ConnectionState` here would drag connectivity types into every
 * ViewModel that only wants to know "refresh now".
 *
 * It fires on **both** edges — reachable again, and no longer reachable — but never for the
 * connection state a screen starts with; see [onlineStateChanges].
 */
@Singleton
class ConnectivityRefresher
    @Inject
    constructor(
        connectionStateProvider: ConnectionStateProvider,
    ) {
        /** Fires once per change of online-ness, in either direction. Never completes. */
        val connectivityChanged: Flow<Unit> =
            connectionStateProvider.state.onlineStateChanges().map { }
    }
