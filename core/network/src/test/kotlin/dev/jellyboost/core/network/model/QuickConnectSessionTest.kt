package dev.jellyboost.core.network.model

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the redacting `toString()` (`scripts/check_redaction.py` catches the shape): a regenerated data-class
 * `toString()` is the easiest way to leak the secret silently. The code is kept — the UI shows it.
 */
class QuickConnectSessionTest {
    private val session = QuickConnectSession(secret = "opaque-poll-secret", code = "482913")

    @Test
    @DisplayName("toString never prints the secret")
    fun toStringRedactsTheSecret() {
        session.toString() shouldNotContain "opaque-poll-secret"
        session.toString() shouldContain "[redacted]"
    }

    @Test
    @DisplayName("toString still shows the user-visible code")
    fun toStringKeepsTheCode() {
        session.toString() shouldContain "482913"
    }
}
