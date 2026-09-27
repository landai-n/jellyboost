package dev.jellyboost.player.cast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The live-region rule both cast status lines (the casting bar, the player's backdrop) follow. */
class CastStatusAnnouncementTest {
    @Test
    fun `a session that has never reconnected is not live, so it does not talk over the transfer message`() {
        castStatusIsLive(isReconnecting = false, hasReconnected = false) shouldBe false
    }

    @Test
    fun `reconnecting is live`() {
        castStatusIsLive(isReconnecting = true, hasReconnected = false) shouldBe true
    }

    @Test
    fun `the return to casting after a reconnect is still live, so the recovery is announced`() {
        castStatusIsLive(isReconnecting = false, hasReconnected = true) shouldBe true
    }
}
