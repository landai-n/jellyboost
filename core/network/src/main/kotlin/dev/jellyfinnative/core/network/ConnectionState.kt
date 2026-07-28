package dev.jellyfinnative.core.network

/**
 * Single source of truth for whether repository calls should take the online or the offline
 * path. Produced by the connectivity monitor + server reachability probe (M6).
 */
enum class ConnectionState {
    ONLINE,
    OFFLINE_NO_NETWORK,
    OFFLINE_SERVER_UNREACHABLE,
    OFFLINE_FORCED,
    ;

    val isOnline: Boolean get() = this == ONLINE
}
