package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * [address] is the exact base URL that answered. Nothing is persisted at resolution time; persistence happens
 * on successful authentication.
 */
data class ResolvedServer(
    val serverId: UUID,
    val name: String,
    val version: String?,
    val address: String,
)
