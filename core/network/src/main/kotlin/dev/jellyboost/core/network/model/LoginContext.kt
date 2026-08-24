package dev.jellyboost.core.network.model

/**
 * Only [publicUsers] is fetched strictly: the disclaimer and the Quick Connect flag degrade to their defaults
 * when the endpoint fails, because neither is worth blocking a login over.
 */
data class LoginContext(
    val publicUsers: List<PublicUserInfo>,
    val loginDisclaimer: String?,
    val quickConnectEnabled: Boolean,
)
