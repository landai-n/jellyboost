package dev.jellyboost.core.network

/** Single source of truth for whether repository calls take the online or the offline path. */
enum class ConnectionState {
    ONLINE,
    OFFLINE_NO_NETWORK,
    OFFLINE_SERVER_UNREACHABLE,
    OFFLINE_FORCED,
    ;

    val isOnline: Boolean get() = this == ONLINE
}
