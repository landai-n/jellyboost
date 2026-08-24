package dev.jellyboost.core.network.model

import java.util.UUID

/**
 * [primaryImageTag] is the avatar image tag; combine it with the server address to build an image URL. A
 * server with "hide users from login" enabled simply returns an empty list.
 */
data class PublicUserInfo(
    val id: UUID,
    val name: String,
    val primaryImageTag: String?,
)
