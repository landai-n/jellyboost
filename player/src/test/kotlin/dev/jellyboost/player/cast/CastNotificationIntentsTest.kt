package dev.jellyboost.player.cast

import android.content.Intent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The half of the notification trampoline a JVM test can reach: which deliveries count, and the flags
 * the app is reopened with. The task behaviour itself (what `CLEAR_TASK` clears) is a device walk.
 */
class CastNotificationIntentsTest {
    @Test
    fun `the trampoline's action is a request to open the casting player`() {
        CastNotificationIntents.opensCastingPlayer(
            CastNotificationIntents.ACTION_OPEN_CASTING_PLAYER,
            flags = 0,
        ) shouldBe
            true
    }

    @Test
    fun `an ordinary launch is not`() {
        CastNotificationIntents.opensCastingPlayer(Intent.ACTION_MAIN, flags = 0) shouldBe false
        CastNotificationIntents.opensCastingPlayer(null, flags = 0) shouldBe false
    }

    @Test
    fun `a relaunch from Recents does not replay an old tap`() {
        CastNotificationIntents.opensCastingPlayer(
            CastNotificationIntents.ACTION_OPEN_CASTING_PLAYER,
            flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        ) shouldBe false
    }

    @Test
    fun `the app is reopened as it was left — never with the flag that wiped its back stack`() {
        val flags = CastNotificationIntents.LAUNCH_FLAGS

        (flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) shouldBe 0
        (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) shouldBe 0
        (flags and Intent.FLAG_ACTIVITY_NEW_TASK) shouldBe Intent.FLAG_ACTIVITY_NEW_TASK
        (flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) shouldBe Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}
