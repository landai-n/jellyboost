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
) {
    /**
     * The generated `toString()` would print [secret] — the KDoc above promises it is never
     * logged, and a `data class` default breaks that promise the first time anything logs or
     * wraps this in an exception message (the NET-02/SEC-12 leak shape, caught here by
     * `scripts/check_redaction.py` on its first run). [code] stays: it is the value the user
     * is deliberately shown on screen.
     */
    override fun toString(): String = "QuickConnectSession(secret=[redacted], code=$code)"
}
