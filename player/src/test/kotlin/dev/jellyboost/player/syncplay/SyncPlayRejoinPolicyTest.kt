package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.time.SyncPlayPinger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection.HTTP_FORBIDDEN

/**
 * Exercised through the real controller (see [SyncPlayControllerTestBase]): these scenarios
 * predate the `SyncPlayRejoinPolicy` extraction and pin the protocol behaviour it always had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SyncPlayRejoinPolicyTest : SyncPlayControllerTestBase() {
    @Test
    fun `leaving during a rejoin tells the server, and the abandoned rejoin never comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            // The rejoin cannot progress, so the session sits in Rejoining with the loop alive.
            fixture.api.getGroupsError = java.io.IOException("still down")
            blipThenDropped(fixture)
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            fixture.api.clearCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>() shouldBe listOf(SyncPlayCall.LeaveGroup)
            fixture.controller.state.value shouldBe SyncPlayState.Idle

            fixture.api.getGroupsError = null
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS * 2)
            runCurrent()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.api.callsOf<SyncPlayCall.JoinGroup>().shouldBeEmpty()
        }

    @Test
    fun `a confirmed connection loss pauses the player at once, and leaves once the rejoin has failed`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("socket closed")
            joinPlaying(fixture)

            fixture.socket.failStreams(java.io.IOException("socket closed"))
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.status.inGroup.value shouldBe false
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            fixture.player.resetCalls()
            fixture.api.clearCalls()
            advanceTimeBy(30_000)
            runCurrent()
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a confirmed loss the connection comes back from is a rejoin, not an ending`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            // Device case: 3s of Wi-Fi off costs more than the grace window once association and
            // the reachability probe are counted, so loss is confirmed before any `NotInGroup`.
            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()
            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()

            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
            fixture.player.playCount shouldBe 0
        }

    @Test
    fun `going offline freezes at once, and is a confirmed loss once the grace window is out`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()

            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS * 2)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `a streak of failed ping cycles is a confirmed loss, once`() =
        runTest {
            val fixture = fixture()
            // Device case: OS still reports "online" while the platform has cut this app's
            // network, so every REST call times out and the socket never comes back.
            fixture.api.failEverySample = java.io.IOException("timeout")
            fixture.api.getGroupsError = java.io.IOException("timeout")
            joinPlaying(fixture)
            fixture.player.resetCalls()

            advanceTimeBy(SyncPlayPinger.FAST_INTERVAL_MS * SyncPlayController.PING_FAILURE_STREAK)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            advanceTimeBy(60_000)
            runCurrent()
            fixture.player.pauseCount shouldBe 1
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `the server saying we are not in the group over a healthy socket tears down without pausing`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
            fixture.player.pauseCount shouldBe 0
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a membership dropped after a blip is taken back, and the player is not started by it`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            blipThenDropped(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
            fixture.player.playCount shouldBe 0

            // The post-join queue re-enters the handshake on the already-open item rather than
            // reloading it, so "rejoin" never looks like "start again".
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded.size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .last()
                .playlistItemId shouldBe playlistItemId

            // The adopted player is already prepared and may never re-buffer, but the owed `ready`
            // (the fallback's) is still sent.
            advanceTimeBy(SyncPlayController.SETTLED_READY_FALLBACK_MS + 1)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .last()
                .playlistItemId shouldBe playlistItemId
            fixture.player.playCount shouldBe 0
        }

    @Test
    fun `a rejoin makes membership fall and rise, so a downloaded item re-mints its session`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)

            blipThenDropped(fixture)

            // The middle `false` is the whole point: `SyncPlayLocalSession` mints on the way back up.
            fixture.membershipEdges shouldBe listOf(false, true, false, true)
        }

    @Test
    fun `a call refused after a blip is a lost membership, and is rejoined too`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.api.clearCalls()
            fixture.api.failNextBuffering = InvalidStatusException(HTTP_FORBIDDEN)

            blip(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe listOf(SyncPlayMessage.Rejoined)
        }

    @Test
    fun `a group that has dissolved is asked after once, and then given up on`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = emptyList()
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blipThenDropped(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.JoinGroup>() shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)

            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)
        }

    @Test
    fun `rejoining is tried exactly three times, two seconds apart, and then reported once`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blipThenDropped(fixture)
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()

            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS - 1)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1

            advanceTimeBy(1)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 2

            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            advanceTimeBy(60_000)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `leaving during a rejoin aborts it, silently`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            blipThenDropped(fixture)
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            fixture.api.clearCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `signing out during a rejoin aborts it, silently`() =
        runTest {
            val fixture = fixture()
            fixture.api.getGroupsError = java.io.IOException("server unreachable")
            joinPlaying(fixture)
            blipThenDropped(fixture)
            fixture.api.clearCalls()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * 4)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `a library this account may not see is never rejoined, however bad the connection was`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blip(fixture)
            fixture.socket.emit(SyncPlayGroupEvent.LibraryAccessDenied)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.LibraryAccessDenied)
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
        }

    @Test
    fun `a removal long after the connection settled is obeyed rather than undone`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            blip(fixture)
            fixture.api.clearCalls()

            // Past REJOIN_TROUBLE_WINDOW_MS, the blip is stale and no longer excuses a removal.
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_TROUBLE_WINDOW_MS + 1)
            runCurrent()
            fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
            fixture.api.callsOf<SyncPlayCall.GetGroups>() shouldBe emptyList()
        }

    @Test
    fun `a membership the background cost us is asked for again when the app comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true
            fixture.messages shouldBe lost + SyncPlayMessage.Rejoined
        }

    @Test
    fun `the group having gone in the meantime is forgotten without a word`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            fixture.api.groups = emptyList()
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe lost

            fixture.api.clearCalls()
            fixture.controller.onAppForegrounded()
            runCurrent()
            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `a foreground re-check that fails is silent, does not loop, and may be tried again`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)

            // OS reports online but the server still doesn't answer — the device's own failure
            // mode (B8): a re-check cannot assume it will work.
            fixture.connection.value = ConnectionState.ONLINE
            fixture.api.getGroupsError = java.io.IOException("no route to host")
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()
            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe lost

            fixture.api.clearCalls()
            advanceTimeBy(60_000)
            runCurrent()
            fixture.api.calls shouldBe emptyList()

            fixture.api.getGroupsError = null
            fixture.controller.onAppForegrounded()
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    @Test
    fun `a loss older than the foreground window is dropped rather than acted on`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            val lost = backgroundedUntilLost(fixture)
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayRejoinPolicy.FOREGROUND_REJOIN_WINDOW_MS + 1)
            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe lost
        }

    @Test
    fun `a group left on purpose is never taken back, however soon the app comes back`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)

            fixture.controller.leaveGroup()
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
        }

    @Test
    fun `signing out forgets a group that was lost, so the next account never sees it`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            backgroundedUntilLost(fixture)
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
        }
}
