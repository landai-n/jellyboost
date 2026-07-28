package dev.jellyfinnative.core.network.model

import java.util.UUID

/**
 * A Jellyfin server announced over the local network (UDP broadcast on port 7359).
 *
 * Produced by `ServerDiscoveryRepository.discoverLocalServers()` for the live list on the
 * server-setup screen. A discovered server has not been probed for compatibility yet — picking
 * one still goes through `ServerDiscoveryRepository.resolveServerAddress`.
 */
data class DiscoveredServer(
    val id: UUID,
    val name: String,
    val address: String,
)
