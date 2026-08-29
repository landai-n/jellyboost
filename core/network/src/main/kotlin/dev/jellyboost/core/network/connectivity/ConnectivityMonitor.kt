package dev.jellyboost.core.network.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Answers only the *transport* question. Whether the Jellyfin server is reachable over that network is
 * [ServerReachabilityProbe]'s job, and the two are combined by [ConnectionStateProvider].
 */
interface ConnectivityMonitor {
    /** Emits the current value on collection and then on every change. */
    val hasNetwork: Flow<Boolean>

    /**
     * `true` while the network the device would use right now is metered — mobile data, a metered
     * hotspot. Emits the current value on collection and then on every change, like [hasNetwork].
     *
     * **`false` when there is no network at all.** "Not on a metered network" is the honest answer
     * when there is no network to be metered: a UI that says "waiting for Wi-Fi" while the device is
     * fully offline would be telling the user something untrue about why nothing is moving.
     */
    val isMetered: Flow<Boolean>
}
