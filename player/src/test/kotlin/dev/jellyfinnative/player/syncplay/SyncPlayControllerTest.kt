package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.millisToTicks
import dev.jellyfinnative.player.session.FakePlayerHandle
import dev.jellyfinnative.player.session.PlayerEvent
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommand
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommandType
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupState
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayRequestKind
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocketState
import dev.jellyfinnative.player.syncplay.time.SyncPlayPinger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [SyncPlayController].
 *
 * Two properties carry the whole feature and neither is visible from a single call site:
 *
 * 1. **No in-group action moves this player locally** (docs/notes/syncplay-m11-plan.md, key
 *    decision 11). Every intent test therefore asserts what the *player* was not asked to do, which
 *    is the only way to state it.
 * 2. **A confirmed connection loss pauses and leaves; a flap does nothing** (key decision 10 as
 *    amended). Both are exercised, because the failure mode of getting the second one wrong — a
 *    group dropped every time a train goes through a tunnel — is invisible in a test that only
 *    covers the first.
 *
 * `runCurrent()` rather than `advanceUntilIdle()` throughout: an in-group controller runs a ping
 * loop and a drift monitor for ever, so "advance until nothing is scheduled" never returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per collaborator would hide the interactions being pinned.
class SyncPlayControllerTest {
    private val origin = Instant.parse("2026-07-30T18:41:00Z")
    private val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    // Joining -------------------------------------------------------------------------------------

    @Test
    fun `joining collects the websocket, so the queue update that follows is not missed`() =
        runTest {
            val fixture = fixture()

            fixture.controller.joinGroup(group())
            runCurrent()

            fixture.socket.collectors shouldBe 2
            fixture.api
                .callsOf<SyncPlayCall.JoinGroup>()
                .single()
                .groupId shouldBe group().id
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.status.inGroup.value shouldBe true

            fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue()))
            runCurrent()

            fixture.host.loaded shouldBe listOf(itemId to 0L)
        }

    @Test
    fun `a failed join leaves nothing running`() =
        runTest {
            val fixture = fixture()
            fixture.api.joinError = IllegalStateException("403")

            fixture.controller.joinGroup(group())
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
            fixture.socket.collectors shouldBe 0
            fixture.messages shouldBe listOf(SyncPlayMessage.JoinFailed)
        }

    @Test
    fun `creating a group joins it`() =
        runTest {
            val fixture = fixture()

            fixture.controller.createGroup("Film night")
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.CreateGroup>()
                .single()
                .name shouldBe "Film night"
            (fixture.controller.state.value as SyncPlayState.InGroup).group shouldBe group()
        }

    @Test
    fun `the join handshake buffers, opens the item paused, then reports ready`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, startTicks = 60_000L.millisToTicks())

            fixture.api
                .callsOf<SyncPlayCall.ReportBuffering>()
                .single()
                .playlistItemId shouldBe playlistItemId
            fixture.host.loaded shouldBe listOf(itemId to 60_000L.millisToTicks())
            // Opening an item is not playing it: the group decides when playback starts.
            fixture.player.hadNoTransportCalls shouldBe true

            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()

            val ready = fixture.api.callsOf<SyncPlayCall.ReportReady>().single()
            ready.playlistItemId shouldBe playlistItemId
            ready.positionTicks shouldBe 60_000L.millisToTicks()
            ready.isPlaying shouldBe false
        }

    @Test
    fun `the server's unpause starts playback at the instant it named, and anchors the drift monitor`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            val whenInstant = now().plusMillis(1_000)
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, whenInstant, positionMs = 60_000))
            runCurrent()

            fixture.player.playCount shouldBe 0

            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.playCount shouldBe 1
            val phase = (fixture.controller.state.value as SyncPlayState.InGroup).phase
            phase shouldBe SyncPlayPhase.Playing(SyncPlayAnchor(60_000L, whenInstant))
        }

    @Test
    fun `a group that is waiting keeps this player paused`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Waiting, SyncPlayRequestKind.Buffer),
            )
            advanceTimeBy(10_000)
            runCurrent()

            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe SyncPlayPhase.Waiting
            fixture.player.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `an item the host already has open is adopted rather than reloaded`() =
        runTest {
            val fixture = fixture()
            // The user opened this item themselves and then the group moved onto it.
            fixture.host.snapshot = SyncPlayHostSnapshot(itemId, 30_000L.millisToTicks(), isPlaying = true)

            joinWithQueue(fixture)

            fixture.host.loaded shouldBe emptyList()
            fixture.api
                .callsOf<SyncPlayCall.ReportReady>()
                .single()
                .positionTicks shouldBe 30_000L.millisToTicks()
        }

    @Test
    fun `an item that cannot be opened is reported rather than left gating the group`() =
        runTest {
            val fixture = fixture()
            fixture.host.loadSucceeds = false

            joinWithQueue(fixture)

            fixture.messages shouldBe listOf(SyncPlayMessage.ItemUnavailable)
        }

    // Intents -------------------------------------------------------------------------------------

    @Test
    fun `every in-group transport intent is a server request and touches no player`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            assertIntent(fixture, SyncPlayCall.RequestPause) { it.requestPause() }
            assertIntent(fixture, SyncPlayCall.RequestUnpause) { it.requestUnpause() }
            assertIntent(fixture, SyncPlayCall.RequestSeek(90_000)) { it.requestSeek(90_000) }
            assertIntent(fixture, SyncPlayCall.RequestNextItem(playlistItemId)) { it.requestNext() }
            assertIntent(fixture, SyncPlayCall.RequestPreviousItem(playlistItemId)) { it.requestPrevious() }
            assertIntent(fixture, SyncPlayCall.SetPlaylistItem(playlistItemId)) {
                it.requestSetPlaylistItem(playlistItemId)
            }
        }

    @Test
    fun `every in-group queue intent is a server request and touches no player`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            assertIntent(fixture, SyncPlayCall.SetNewQueue(listOf(itemId), 0, 0)) { it.setNewQueue(listOf(itemId)) }
            assertIntent(fixture, SyncPlayCall.AddToQueue(listOf(otherItemId), SyncPlayQueueMode.QueueNext)) {
                it.addToQueue(listOf(otherItemId), SyncPlayQueueMode.QueueNext)
            }
            assertIntent(fixture, SyncPlayCall.MovePlaylistItem(playlistItemId, 2)) {
                it.moveQueueItem(playlistItemId, 2)
            }
            assertIntent(fixture, SyncPlayCall.RemoveFromPlaylist(listOf(playlistItemId), false, false)) {
                it.removeFromQueue(listOf(playlistItemId))
            }
            assertIntent(fixture, SyncPlayCall.SetShuffleMode(SyncPlayShuffleMode.Shuffle)) {
                it.setShuffle(SyncPlayShuffleMode.Shuffle)
            }
            assertIntent(fixture, SyncPlayCall.SetRepeatMode(SyncPlayRepeatMode.All)) {
                it.setRepeat(SyncPlayRepeatMode.All)
            }
        }

    @Test
    fun `an intent outside a group does nothing at all`() =
        runTest {
            val fixture = fixture()

            fixture.controller.requestPause()
            runCurrent()

            fixture.api.calls shouldBe emptyList()
        }

    @Test
    fun `an item playing to its end asks the group to move on, it does not move on by itself`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()
            fixture.player.resetCalls()

            fixture.player.emit(PlayerEvent.Ended)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.RequestNextItem>()
                .single()
                .playlistItemId shouldBe playlistItemId
            fixture.player.hadNoTransportCalls shouldBe true
        }

    // Host attachment -------------------------------------------------------------------------------

    @Test
    fun `detaching the player asks the group to stop waiting on us, and keeps the membership`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.controller.detachHost(fixture.host)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.SetIgnoreWait>()
                .single()
                .ignoreWait shouldBe true
            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
        }

    @Test
    fun `a stale host cannot detach the player that replaced it`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.controller.detachHost(FakeSyncPlayPlaybackHost())
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.SetIgnoreWait>() shouldBe emptyList()
        }

    @Test
    fun `a queue update with no player attached asks the app to open one`() =
        runTest {
            val fixture = fixture(attachHost = false)

            joinWithQueue(fixture, startTicks = 12_000L.millisToTicks())

            fixture.launchRequests shouldBe listOf(SyncPlayLaunchRequest(itemId, 12_000L.millisToTicks()))
            fixture.host.loaded shouldBe emptyList()
        }

    @Test
    fun `re-attaching a player clears the ignore-wait and opens what the group is on`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.controller.detachHost(fixture.host)
            runCurrent()
            fixture.api.clearCalls()

            fixture.controller.attachHost(fixture.host)
            runCurrent()

            fixture.api
                .callsOf<SyncPlayCall.SetIgnoreWait>()
                .single()
                .ignoreWait shouldBe false
        }

    // Losing the group ------------------------------------------------------------------------------

    @Test
    fun `a confirmed connection loss pauses the player once, leaves, and then touches nothing`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)

            fixture.socket.failStreams(java.io.IOException("socket closed"))
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
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
    fun `going offline while in a group is a confirmed loss too`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)

            fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        }

    @Test
    fun `a socket state flap the SDK recovers from does not drop the group`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.socket.setConnectionState(SyncPlaySocketState.Disconnected(java.io.IOException("blip")))
            runCurrent()
            fixture.socket.setConnectionState(SyncPlaySocketState.Connecting)
            runCurrent()
            fixture.socket.setConnectionState(SyncPlaySocketState.Connected)
            runCurrent()

            fixture.controller.state.value
                .shouldBeInstanceOf<SyncPlayState.InGroup>()
            fixture.player.pauseCount shouldBe 0
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `the server saying we are not in the group tears down without pausing`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
            fixture.player.pauseCount shouldBe 0
        }

    @Test
    fun `a group that no longer exists tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.GroupGone)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.GroupEnded)
        }

    @Test
    fun `a library this account may not see tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.LibraryAccessDenied)
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.LibraryAccessDenied)
        }

    @Test
    fun `being removed from our own group tears down`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)

            fixture.socket.emit(SyncPlayGroupEvent.Left(group().id))
            runCurrent()

            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.messages shouldBe listOf(SyncPlayMessage.RemovedFromGroup)
        }

    @Test
    fun `leaving tells the server and stops everything, without touching playback`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.controller.leaveGroup()
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.socket.collectors shouldBe 0
            // Leaving deliberately does not pause: playback simply carries on, now solo.
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.messages shouldBe emptyList()
        }

    @Test
    fun `signing out leaves the group`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.api.clearCalls()

            fixture.session.value = SessionState.LoggedOut
            runCurrent()

            fixture.api.callsOf<SyncPlayCall.LeaveGroup>().size shouldBe 1
            fixture.controller.state.value shouldBe SyncPlayState.Idle
            fixture.status.inGroup.value shouldBe false
        }

    // Fixture ---------------------------------------------------------------------------------------

    private fun TestScope.assertIntent(
        fixture: Fixture,
        expected: SyncPlayCall,
        intent: (SyncPlayController) -> Unit,
    ) {
        fixture.api.clearCalls()
        fixture.player.resetCalls()

        intent(fixture.controller)
        runCurrent()

        fixture.api.calls shouldBe listOf(expected)
        fixture.player.hadNoTransportCalls shouldBe true
    }

    /** Joins, and lets the server publish a queue with one entry. */
    private suspend fun TestScope.joinWithQueue(
        fixture: Fixture,
        startTicks: Long = 0L,
    ) {
        fixture.controller.joinGroup(group())
        runCurrent()
        fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue(startTicks)))
        runCurrent()
    }

    /** Joins, readies up, and lets the group start playing — the state most failures matter in. */
    private suspend fun TestScope.joinPlaying(fixture: Fixture) {
        joinWithQueue(fixture)
        fixture.player.emit(PlayerEvent.Ready)
        runCurrent()
        fixture.socket.emit(command(SyncPlayCommandType.Unpause, now(), positionMs = 0))
        runCurrent()
        (fixture.controller.state.value as SyncPlayState.InGroup).phase.shouldBeInstanceOf<SyncPlayPhase.Playing>()
    }

    private fun TestScope.now(): Instant = origin.plusMillis(currentTime)

    private fun command(
        type: SyncPlayCommandType,
        whenInstant: Instant,
        positionMs: Long?,
    ) = SyncPlayCommand(
        type = type,
        whenInstant = whenInstant,
        positionTicks = positionMs?.millisToTicks(),
        playlistItemId = playlistItemId,
        emittedAt = whenInstant,
    )

    private fun queue(startTicks: Long = 0L) =
        SyncPlayGroupQueue(
            entries = listOf(SyncPlayQueueEntry(itemId, playlistItemId)),
            playingItemIndex = 0,
            startPositionTicks = startTicks,
            isPlaying = false,
            shuffleMode = SyncPlayShuffleMode.Sorted,
            repeatMode = SyncPlayRepeatMode.None,
            reason = SyncPlayQueueUpdateReason.NewPlaylist,
            lastUpdate = origin,
        )

    /** Everything a test needs to drive the controller and to see what it did. */
    @Suppress("LongParameterList") // A test-only bag of collaborators; grouping them would only hide them.
    private class Fixture(
        val controller: SyncPlayController,
        val api: FakeSyncPlayApi,
        val socket: FakeSyncPlaySocket,
        val player: FakePlayerHandle,
        val host: FakeSyncPlayPlaybackHost,
        val status: SyncPlayStatusHolder,
        val connection: MutableStateFlow<ConnectionState>,
        val session: MutableStateFlow<SessionState>,
        val messages: List<SyncPlayMessage>,
        val launchRequests: List<SyncPlayLaunchRequest>,
    )

    private fun TestScope.fixture(attachHost: Boolean = true): Fixture {
        val clock = VirtualClock(testScheduler, origin)
        val timeSync = timeSyncWithOffset(clock, offsetMillis = 0L)
        val api = FakeSyncPlayApi(clock)
        api.createdGroup = group()
        val socket = FakeSyncPlaySocket()
        val player = FakePlayerHandle()
        val host = FakeSyncPlayPlaybackHost()
        val status = SyncPlayStatusHolder()
        val main = StandardTestDispatcher(testScheduler)

        val connection = MutableStateFlow(ConnectionState.ONLINE)
        val connectionProvider = mockk<ConnectionStateProvider>()
        every { connectionProvider.state } returns connection

        val session = MutableStateFlow<SessionState>(loggedIn())
        val sessionHolder = mockk<SessionStateHolder>()
        every { sessionHolder.state } returns session

        val controller =
            SyncPlayController(
                api = api,
                socket = socket,
                timeSync = timeSync,
                scheduler = SyncPlayCommandScheduler(player, timeSync, clock, backgroundScope, main),
                driftMonitor = SyncPlayDriftMonitor(player, timeSync, main),
                pinger = SyncPlayPinger(api, timeSync),
                statusHolder = status,
                playerHandle = player,
                connectionState = connectionProvider,
                sessionStateHolder = sessionHolder,
                scope = backgroundScope,
                mainDispatcher = main,
            )

        val messages = mutableListOf<SyncPlayMessage>()
        val launchRequests = mutableListOf<SyncPlayLaunchRequest>()
        backgroundScope.launch { controller.messages.collect { messages += it } }
        backgroundScope.launch { controller.launchRequests.collect { launchRequests += it } }
        runCurrent()

        player.snapshot = PlaybackSnapshot()
        if (attachHost) controller.attachHost(host)
        runCurrent()

        return Fixture(
            controller,
            api,
            socket,
            player,
            host,
            status,
            connection,
            session,
            messages,
            launchRequests,
        )
    }

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            userId = UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
            userName = "casey",
            serverName = "home",
            serverVersion = "10.10.0",
        )
}
