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
 * The connection-loss and rejoin scenarios — the behaviour `SyncPlayRejoinPolicy` owns.
 *
 * Exercised through the real controller (see [SyncPlayControllerTestBase]): every scenario here
 * predates the extraction and pins the same observable protocol behaviour it always did.
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

            // The leave reached the server even though the rejoin owned the session (audit SP-10)...
            fixture.api.callsOf<SyncPlayCall.LeaveGroup>() shouldBe listOf(SyncPlayCall.LeaveGroup)
            fixture.controller.state.value shouldBe SyncPlayState.Idle

            // ...and the loop the leave cancelled cannot re-enter the group behind the user's back.
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
            // The connection really is gone, so the group list cannot be read either.
            fixture.api.getGroupsError = java.io.IOException("socket closed")
            joinPlaying(fixture)

            fixture.socket.failStreams(java.io.IOException("socket closed"))
            runCurrent()

            // Frozen immediately — the rejoin never lets playback run on out of step.
            fixture.player.pauseCount shouldBe 1
            fixture.status.inGroup.value shouldBe false
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)

            // Nothing keeps running: no drift correction, no ping, no scheduled command.
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

            // The device case exactly: three seconds of Wi-Fi off costs more than the grace window
            // once association and the reachability probe are counted, so the loss is confirmed
            // before anything has heard a `NotInGroup`.
            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()
            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.Rejoining>()
            // Not a single call spent on a network that is known to be down.
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

            // Frozen immediately, so a hard Wi-Fi kill still stops within the window rather than at
            // the end of it — but the group is not given up on yet.
            fixture.player.pauseCount shouldBe 1
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.messages shouldBe emptyList()

            advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
            runCurrent()

            // Still not given up on: the rejoin gets its attempts first, all of them spent waiting
            // for a network that never comes back.
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
            // The device case: the OS still says "online" while the platform has cut this app's
            // network, so every REST call times out and the socket never comes back.
            fixture.api.failEverySample = java.io.IOException("timeout")
            fixture.api.getGroupsError = java.io.IOException("timeout")
            joinPlaying(fixture)
            fixture.player.resetCalls()

            advanceTimeBy(SyncPlayPinger.FAST_INTERVAL_MS * SyncPlayController.PING_FAILURE_STREAK)
            runCurrent()

            // Frozen at once, and given up on only after the rejoin attempts have failed too.
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
            // Nothing was wrong with the connection, so the removal was somebody's decision: obeyed,
            // not undone. Not even the group list is asked for.
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
            // Frozen throughout: the group's own answer to the handshake is what resumes anyone.
            fixture.player.playCount shouldBe 0

            // The server's post-join queue re-enters the handshake on the item already open, rather
            // than reloading it — which is what keeps "rejoin" from looking like "start again".
            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded.size shouldBe 1
            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .last()
                .playlistItemId shouldBe playlistItemId

            // The adopted player is prepared already and may never re-buffer, so the owed `ready` is
            // the fallback's (DECISIONS.md, 2026-07-31) — but it is still owed and still sent.
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

            // Idle, in the group, out of it while the server did not have us, in it again. The middle
            // `false` is the whole point: `SyncPlayLocalSession` mints on the way back up.
            fixture.membershipEdges shouldBe listOf(false, true, false, true)
        }

    @Test
    fun `a call refused after a blip is a lost membership, and is rejoined too`() =
        runTest {
            val fixture = fixture()
            fixture.api.groups = listOf(group())
            joinPlaying(fixture)
            fixture.api.clearCalls()
            // The re-negotiation that follows the blip is the first call to find out.
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
            // Nobody else was in it, so the server removed the group along with this session.
            fixture.api.groups = emptyList()
            joinPlaying(fixture)
            fixture.api.clearCalls()

            blipThenDropped(fixture)

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.JoinGroup>() shouldBe emptyList()
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)

            // And no attempt keeps running behind the teardown.
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

            // Once out, we stay out: no background loop keeps asking.
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

            // The blip is ancient history by the time this arrives, so it explains nothing.
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

            // The network is back long before the user is, and nothing is running to notice it.
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

            // Everyone else left too, so the server disposed of it.
            fixture.api.groups = emptyList()
            fixture.connection.value = ConnectionState.ONLINE
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.onAppForegrounded()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.GetGroups>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            // Silent: a re-check that finds nothing must not announce itself on every foreground.
            fixture.messages shouldBe lost

            // And forgotten, so the next foreground spends nothing on it.
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

            // Online as far as the OS is concerned, but the server is still not answering — the
            // device's own failure mode (B8), and the reason a re-check cannot assume it will work.
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

            // Nothing keeps trying in the background...
            fixture.api.clearCalls()
            advanceTimeBy(60_000)
            runCurrent()
            fixture.api.calls shouldBe emptyList()

            // ...and the memory survived the failure, so the next foreground gets it back.
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
