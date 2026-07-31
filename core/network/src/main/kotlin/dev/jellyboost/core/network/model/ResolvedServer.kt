package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * A server address that has been probed and found usable.
 *
 * [address] is the exact base URL that answered — it is what gets stored as a
 * `ServerAddressEntity` and configured on the SDK `ApiClient` once the user signs in.
 * Nothing is persisted at resolution time; persistence happens on successful authentication.
 */
data class ResolvedServer(
    val serverId: UUID,
    val name: String,
    val version: String?,
    val address: String,
)
