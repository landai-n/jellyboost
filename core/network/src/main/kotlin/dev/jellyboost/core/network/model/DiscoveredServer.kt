package dev.jellyboost.core.network.model

import java.util.UUID

/** A discovered server has not been probed for compatibility yet — picking one still goes through resolution. */
data class DiscoveredServer(
    val id: UUID,
    val name: String,
    val address: String,
)
