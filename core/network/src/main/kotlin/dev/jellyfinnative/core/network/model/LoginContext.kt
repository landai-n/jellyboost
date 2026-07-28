package dev.jellyfinnative.core.network.model

/**
 * Everything the login screen needs to render before the user types anything.
 *
 * Only [publicUsers] is fetched strictly: the disclaimer and the Quick Connect flag degrade to
 * their defaults (`null` / `false`) when the corresponding endpoint fails, because neither is
 * worth blocking a login over.
 */
data class LoginContext(
    val publicUsers: List<PublicUserInfo>,
    val loginDisclaimer: String?,
    val quickConnectEnabled: Boolean,
)
