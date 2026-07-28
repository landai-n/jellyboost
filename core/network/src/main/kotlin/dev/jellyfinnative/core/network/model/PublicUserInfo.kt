package dev.jellyfinnative.core.network.model

import java.util.UUID

/**
 * One of the users a server advertises on its login screen (`getPublicUsers`).
 *
 * [primaryImageTag] is the avatar image tag; combine it with the server address to build an
 * image URL. Servers with "hide users from login" enabled simply return an empty list.
 */
data class PublicUserInfo(
    val id: UUID,
    val name: String,
    val primaryImageTag: String?,
)
