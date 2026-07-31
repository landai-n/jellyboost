package dev.jellyboost.core.network.model

/**
 * A Quick Connect request that has been initiated on the server.
 *
 * [code] is the short human-readable code shown to the user, which they type into an
 * already-authenticated Jellyfin client. [secret] is the opaque handle this device polls with
 * and finally exchanges for an access token — it is a credential in its own right and must
 * never be logged or shown.
 */
data class QuickConnectSession(
    val secret: String,
    val code: String,
)
