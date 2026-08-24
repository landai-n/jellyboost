package dev.jellyboost.core.network.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Reports whether this device currently has a network that could carry traffic.
 *
 * Deliberately answers only the *transport* question. Whether the Jellyfin server is actually
 * reachable over that network is [ServerReachabilityProbe]'s job, and the two are combined by
 * [ConnectionStateProvider].
 */
interface ConnectivityMonitor {
    /**
     * `true` while a default network with internet capability is available.
     *
     * Emits the current value on collection and then on every change. Cold: each collector
     * registers its own callback, so collect it once and share it.
     */
    val hasNetwork: Flow<Boolean>
}
