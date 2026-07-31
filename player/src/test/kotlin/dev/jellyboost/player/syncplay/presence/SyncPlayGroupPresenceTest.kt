package dev.jellyboost.player.syncplay.presence

import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [syncPlayPresenceDemanded].
 *
 * The rule this pins is the whole of the background fix (DECISIONS.md 2026-07-31), and it is worth
 * a test of its own precisely because its failures are silent in both directions: demand it too
 * rarely and a group is dropped forty seconds after the user presses Home, demand it too eagerly and
 * the app leaves an ongoing notification standing over nothing.
 */
class SyncPlayGroupPresenceTest {
    @Test
    fun `a group with nothing playing is what the service exists for`() {
        syncPlayPresenceDemanded(inGroup(), playbackServiceRunning = false) shouldBe true
    }

    @Test
    fun `playback taking over releases it — one foreground service is enough`() {
        syncPlayPresenceDemanded(inGroup(), playbackServiceRunning = true) shouldBe false
    }

    @Test
    fun `no group, no service — leaving and signing out both land on Idle`() {
        syncPlayPresenceDemanded(SyncPlayState.Idle, playbackServiceRunning = false) shouldBe false
        syncPlayPresenceDemanded(SyncPlayState.Idle, playbackServiceRunning = true) shouldBe false
    }

    @Test
    fun `joining is held too, because being killed mid-handshake is no better`() {
        syncPlayPresenceDemanded(SyncPlayState.Joining, playbackServiceRunning = false) shouldBe true
    }

    @Test
    fun `rejoining is held above all, because the connection has already misbehaved once`() {
        val rejoining = SyncPlayState.Rejoining(group(), attempt = 2)

        syncPlayPresenceDemanded(rejoining, playbackServiceRunning = false) shouldBe true
        syncPlayPresenceDemanded(rejoining, playbackServiceRunning = true) shouldBe false
    }

    private fun inGroup() =
        SyncPlayState.InGroup(
            group(),
            queue = null,
            groupState = SyncPlayGroupState.Idle,
            phase = SyncPlayPhase.Waiting,
        )
}
