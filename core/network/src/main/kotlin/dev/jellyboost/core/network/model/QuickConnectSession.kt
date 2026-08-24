package dev.jellyboost.core.network.model

/**
 * [code] is the short human-readable code the user types into an already-authenticated client. [secret] is
 * the opaque handle this device polls with and exchanges for an access token — a credential in its own right,
 * which must never be logged or shown.
 */
data class QuickConnectSession(
    val secret: String,
    val code: String,
) {
    /**
     * The generated `toString()` would print [secret]; `scripts/check_redaction.py` catches that shape. [code]
     * stays — it is the value the user is deliberately shown.
     */
    override fun toString(): String = "QuickConnectSession(secret=[redacted], code=$code)"
}
