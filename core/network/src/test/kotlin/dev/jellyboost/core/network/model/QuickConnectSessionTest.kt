package dev.jellyboost.core.network.model

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins [QuickConnectSession]'s redacting `toString()` (the NET-02/SEC-12 leak shape, found by
 * `scripts/check_redaction.py` on its first run): the class's own KDoc promises the secret is
 * never logged, and a regenerated data-class `toString()` is the easiest way to break that
 * silently. The code is deliberately kept — it is the value the UI shows the user.
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
