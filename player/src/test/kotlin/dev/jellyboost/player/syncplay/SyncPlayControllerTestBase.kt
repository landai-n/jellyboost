package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.time.SyncPlayPinger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import java.time.Instant
import java.util.UUID

/**
 * The shared harness for the SyncPlay coordinator suites.
 *
 * One fixture, three suites: [SyncPlayControllerTest] (join handshake, intents, queue
 * reconciliation, host attachment, teardown paths), [SyncPlayRejoinPolicyTest] (connection loss,
 * auto-rejoin, the foreground re-check) and [SyncPlayRecoveryNetsTest] (the B3 self-sync and
 * pause safety nets). All three drive the real controller with its real collaborators wired to
 * fakes — the extractions (`SyncPlayRejoinPolicy`, `SyncPlayRecoveryNets`) are deliberately
 * covered through the controller's public surface, so the tests pin protocol behaviour rather
 * than the seams' shapes.
 *
 * `runCurrent()` rather than `advanceUntilIdle()` throughout: an in-group controller runs a ping
 * loop and a drift monitor for ever, so "advance until nothing is scheduled" never returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class SyncPlayControllerTestBase {
    protected val origin = Instant.parse("2026-07-30T18:41:00Z")
    protected val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    protected val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    protected val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    protected val otherPlaylistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    // Fixture ---------------------------------------------------------------------------------------

    /**
     * The device failure in full: the app is backgrounded, the platform quietly cuts its network,
     * and the group is gone by the time anything can be done about it.
     *
     * @return the messages emitted by the loss, so a test can assert that the foreground re-check
     *   added nothing to them.
     */
    protected suspend fun TestScope.backgroundedUntilLost(fixture: Fixture): List<SyncPlayMessage> {
        joinPlaying(fixture)
        fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
        runCurrent()
        advanceTimeBy(SyncPlayController.CONNECTIVITY_GRACE_MS + 1)
        runCurrent()
        advanceTimeBy(SyncPlayRejoinPolicy.REJOIN_RETRY_DELAY_MS * SyncPlayRejoinPolicy.REJOIN_MAX_ATTEMPTS * 2)
        runCurrent()
        fixture.controller.state.value shouldBe SyncPlayState.Idle
        fixture.messages shouldBe listOf(SyncPlayMessage.ConnectionLost)
        return fixture.messages.toList()
    }

    /** A Wi-Fi blip the grace window rides out — and the trouble that explains a later removal. */
    protected fun TestScope.blip(fixture: Fixture) {
        fixture.connection.value = ConnectionState.OFFLINE_NO_NETWORK
        runCurrent()
        advanceTimeBy(BLIP_MS)
        fixture.connection.value = ConnectionState.ONLINE
        runCurrent()
    }

    /**
     * The device case in full: a blip the client survives and the *server* does not.
     *
     * The dropped websocket ends the session, `OnSessionEnded` leaves the group on this client's
     * behalf, and the next request comes back `NotInGroup`.
     */
    protected suspend fun TestScope.blipThenDropped(fixture: Fixture) {
        blip(fixture)
        fixture.socket.emit(SyncPlayGroupEvent.NotInGroup)
        runCurrent()
    }

    protected fun TestScope.assertIntent(
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

    /** Joins, and lets the server publish a queue — one entry unless a test supplies another. */
    protected suspend fun TestScope.joinWithQueue(
        fixture: Fixture,
        startTicks: Long = 0L,
        queue: SyncPlayGroupQueue = queue(startTicks),
    ) {
        fixture.controller.joinGroup(group())
        runCurrent()
        fixture.socket.emit(SyncPlayGroupEvent.QueueChanged(queue))
        runCurrent()
    }

    /** Joins, readies up, and lets the group start playing — the state most failures matter in. */
    protected suspend fun TestScope.joinPlaying(fixture: Fixture) {
        joinWithQueue(fixture)
        fixture.player.emit(PlayerEvent.Ready)
        runCurrent()
        fixture.socket.emit(command(SyncPlayCommandType.Unpause, now(), positionMs = 0))
        runCurrent()
        (fixture.controller.state.value as SyncPlayState.InGroup).phase.shouldBeInstanceOf<SyncPlayPhase.Playing>()
    }

    protected fun TestScope.now(): Instant = origin.plusMillis(currentTime)

    protected fun command(
        type: SyncPlayCommandType,
        whenInstant: Instant,
        positionMs: Long?,
        emittedAt: Instant = whenInstant,
    ) = SyncPlayCommand(
        type = type,
        whenInstant = whenInstant,
        positionTicks = positionMs?.millisToTicks(),
        playlistItemId = playlistItemId,
        emittedAt = emittedAt,
    )

    protected fun queue(startTicks: Long = 0L) =
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

    /**
     * Two slots holding two different items, with the group on [playingIndex].
     *
     * Two is the smallest queue that can tell "the group moved on" apart from "the queue was
     * re-sent", which is the whole of Phase 4's reconciliation.
     */
    protected fun twoItemQueue(
        playingIndex: Int,
        startTicks: Long = 0L,
        reason: SyncPlayQueueUpdateReason = SyncPlayQueueUpdateReason.NewPlaylist,
    ) = queue(startTicks).copy(
        entries =
            listOf(
                SyncPlayQueueEntry(itemId, playlistItemId),
                SyncPlayQueueEntry(otherItemId, otherPlaylistItemId),
            ),
        playingItemIndex = playingIndex,
        reason = reason,
    )

    /** Everything a test needs to drive the controller and to see what it did. */
    @Suppress("LongParameterList") // A test-only bag of collaborators; grouping them would only hide them.
    protected class Fixture(
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
        /**
         * Every change of "is this session in a group", oldest first.
         *
         * Exactly the flow `PlayerSyncPlayBridge.membership` is built from, and collected here
         * because a rejoin has to make it go `false` and back — that edge is what re-mints the
         * server-visible session of a downloaded file (`SyncPlayLocalSession`). A `StateFlow`
         * conflates, so "the rejoin was too quick for the collector" is a real failure mode and this
         * is where it would show.
         */
        val membershipEdges: List<Boolean>,
    )

    protected fun TestScope.fixture(attachHost: Boolean = true): Fixture {
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
                clock = clock,
                scope = backgroundScope,
                mainDispatcher = main,
            )

        val recorded = record(controller)
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
            recorded.messages,
            recorded.launchRequests,
            recorded.membershipEdges,
        )
    }

    /** Everything the controller publishes, collected into lists the assertions can read. */
    protected fun TestScope.record(controller: SyncPlayController): Recorded {
        val recorded = Recorded()
        backgroundScope.launch { controller.messages.collect { recorded.messages += it } }
        backgroundScope.launch { controller.launchRequests.collect { recorded.launchRequests += it } }
        backgroundScope.launch {
            controller.state
                .map { it is SyncPlayState.InGroup }
                .distinctUntilChanged()
                .collect { recorded.membershipEdges += it }
        }
        runCurrent()
        return recorded
    }

    protected class Recorded(
        val messages: MutableList<SyncPlayMessage> = mutableListOf(),
        val launchRequests: MutableList<SyncPlayLaunchRequest> = mutableListOf(),
        val membershipEdges: MutableList<Boolean> = mutableListOf(),
    )

    protected companion object {
        /** A blip well inside [SyncPlayController.CONNECTIVITY_GRACE_MS] — the device's own two seconds. */
        const val BLIP_MS = 2_000L

        /**
         * How long ago a group published the position it is measured from.
         *
         * Deliberately far larger than [SyncPlayDriftMonitor.MAX_DRIFT_MS]: the whole point of the
         * honest anchor is that this interval belongs in the arithmetic, and a value the drift
         * monitor would have absorbed anyway would prove nothing.
         */
        const val GROUP_HEAD_START_MS = 20_000L
    }

    protected fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            userId = UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
            userName = "casey",
            serverName = "home",
            serverVersion = "10.10.0",
        )
}
