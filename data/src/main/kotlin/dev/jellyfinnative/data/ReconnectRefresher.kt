package dev.jellyfinnative.data

import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.connectivity.reconnectEdges
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "The server is reachable again — what you are showing may be stale."
 *
 * `DelegatingJellyfinRepository` picks its source per *call*, which is enough for anything fetched
 * after connectivity returns but does nothing for a screen that already fetched: it keeps the
 * offline answer until the user leaves and comes back (STATUS.md, M6 known issues). Screens collect
 * this and re-run whatever load they already have.
 *
 * The type is a `Flow<Unit>` and nothing else on purpose. Feature modules depend on `:data`, not on
 * `:core:network`, so exposing `ConnectionState` here would drag connectivity types into every
 * ViewModel that only wants to know "refresh now".
 *
 * It emits **only on a `false → true` edge**, never for the connection state a screen starts with —
 * see [reconnectEdges].
 */
@Singleton
class ReconnectRefresher
    @Inject
    constructor(
        connectionStateProvider: ConnectionStateProvider,
    ) {
        /** Fires once per return to a reachable server. Never completes. */
        val reconnected: Flow<Unit> = connectionStateProvider.state.reconnectEdges()
    }
