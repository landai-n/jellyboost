package dev.jellyboost.core.network.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Answers only the *transport* question. Whether the Jellyfin server is reachable over that network is
 * [ServerReachabilityProbe]'s job, and the two are combined by [ConnectionStateProvider].
 */
interface ConnectivityMonitor {
    /** Emits the current value on collection and then on every change. */
    val hasNetwork: Flow<Boolean>
}
