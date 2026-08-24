package dev.jellyboost.core.datastore

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins [StoredSession]'s redacting `toString()`: the class's own contract says the token must
 * never reach a log, and a regenerated data-class `toString()` is the easiest way to break that
 * silently.
 */
class StoredSessionTest {
    private val session =
        StoredSession(
            serverId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            userId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            accessToken = "super-secret-token",
        )

    @Test
    @DisplayName("toString never prints the access token")
    fun toStringRedactsTheToken() {
        session.toString() shouldNotContain "super-secret-token"
        session.toString() shouldContain "<redacted>"
    }

    @Test
    @DisplayName("toString still identifies the session it describes")
    fun toStringKeepsTheIdentifiers() {
        session.toString() shouldContain session.serverId.toString()
        session.toString() shouldContain session.userId.toString()
    }
}
