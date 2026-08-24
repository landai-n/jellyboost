package dev.jellyboost.player.syncplay

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRequestKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The B3 timer safety nets. Exercised through the real controller (see
 * [SyncPlayControllerTestBase]): these scenarios predate the `SyncPlayRecoveryNets` extraction
 * and pin the protocol behaviour it always had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SyncPlayRecoveryNetsTest : SyncPlayControllerTestBase() {
    @Test
    fun `a handshake into a playing group syncs itself when the group sends no command`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture, queue = twoItemQueue(playingIndex = 0))
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, now(), positionMs = 0))
            runCurrent()

            fixture.player.emit(PlayerEvent.Ended)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.QueueChanged(
                    twoItemQueue(playingIndex = 1, reason = SyncPlayQueueUpdateReason.NextItem),
                ),
            )
            runCurrent()
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS)
            runCurrent()

            // Stage one asks rather than acts: the server reads a request for the state it is
            // already in as "client got lost" and re-sends the authoritative command.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            (fixture.controller.state.value as SyncPlayState.InGroup)
                .phase
                .shouldBeInstanceOf<SyncPlayPhase.Playing>()
            // Recovering must not re-open the handshake: another ready is what the storm is made of.
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            // The second window is a fallback, not another ask: one request per episode.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
        }

    @Test
    fun `a playing group that sent no command is asked to repeat itself, and the repeat lands`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS)
            runCurrent()

            // Server-side: `PlayingGroupState.HandleRequest(UnpauseGroupRequest)` with
            // prevState == Playing re-sends the current command to this session alone.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            val whenInstant = now()
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, whenInstant, positionMs = 90_000))
            runCurrent()

            // The group's own timeline, exact — not the inferred anchor the fallback would use.
            fixture.player.playCount shouldBe 1
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe
                SyncPlayPhase.Playing(SyncPlayAnchor(90_000L, whenInstant))

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
        }

    @Test
    fun `a paused group that sent no command is asked to repeat itself, and the repeat lands`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS)
            runCurrent()

            // Server-side: `PausedGroupState.HandleRequest(PauseGroupRequest)` with
            // prevState == Paused answers the same way, its command carrying the parked position.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.pauseCount shouldBe 0

            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(90_000L)

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
        }

    @Test
    fun `a state flip inside the second window stands the local fallback down too`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS)
            runCurrent()
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1

            // The re-armed net is the same job, so the ordinary disarm still reaches it.
            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS - 1)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `a self-sync measures from the instant the group's position was true, not from now`() =
        runTest {
            val fixture = fixture()
            // Published "60s" twenty seconds ago while still playing puts the group at 80s now.
            // Pairing that position with *this* moment is the browser-resume desync from the bug
            // report: the member lands 20s short and the drift monitor defends the short timeline.
            val stale =
                queue(startTicks = 60_000L.millisToTicks())
                    .copy(lastUpdate = origin.minusMillis(GROUP_HEAD_START_MS))
            joinWithQueue(fixture, queue = stale)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(
                    60_000L + GROUP_HEAD_START_MS +
                        SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS,
                )
            // The anchor handed to the drift monitor is the queue's own instant, so later
            // corrections are measured against the group's real timeline.
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe
                SyncPlayPhase.Playing(SyncPlayAnchor(60_000L, stale.lastUpdate))
        }

    @Test
    fun `an unpause arriving in time stands the safety net down`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // The net is what would call `play` again; the drift monitor's corrective seeks (which
            // this fake player earns by never advancing) are not it.
            fixture.player.playCount shouldBe 0
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>() shouldBe emptyList()
        }

    @Test
    fun `a resume out of a pause anchors on the parked player, not the stale queue`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 90_000, isPlaying = false)
            fixture.player.resetCalls()

            // A minute parked makes the queue's own reading (0s, published at join) long stale.
            advanceTimeBy(60_000)
            // The resume's command frame is lost; only the state update arrives.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Unpause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            // Fallback resumes from the parked position plus its own delay — not the queue's
            // position plus a minute of wall clock, which was a 23-minute jump measured on device.
            fixture.player.seekedToMs shouldBe
                listOf(
                    90_000L + SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS +
                        SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS,
                )
        }

    @Test
    fun `a group that says it is paused and sends no command pauses this member`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()
            fixture.api.clearCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
            val inGroup = fixture.controller.state.value as SyncPlayState.InGroup
            inGroup.groupState shouldBe SyncPlayGroupState.Paused
            inGroup.phase shouldBe SyncPlayPhase.Paused

            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS)
            runCurrent()

            // Stage one: a group already paused answers a redundant pause request by re-sending
            // its own pause to this session.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // `Paused` shuts the drift monitor off, so a member playing on alone is the one
            // failure the phase cannot even see.
            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe emptyList()
            fixture.player.playCount shouldBe 0
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
        }

    @Test
    fun `the group's own pause arriving in time stands the pause net down`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 30_000))
            runCurrent()

            fixture.player.pauseCount shouldBe 1

            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // An applied command stands both stages down, so nothing is asked for either.
            fixture.player.pauseCount shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `the pause net does nothing to a player that is already stopped`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.player.resetCalls()

            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
            // A stopped player is already where the group is; asking per lost pause would just be
            // traffic for its own sake.
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `joining a group that is already paused stops a player that was running solo`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(isPlaying = true)

            fixture.controller.joinGroup(group(state = SyncPlayGroupState.Paused))
            runCurrent()

            (fixture.controller.state.value as SyncPlayState.InGroup).groupState shouldBe SyncPlayGroupState.Paused
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
        }

    @Test
    fun `a group that stops playing cancels the self-sync armed behind it`() =
        runTest {
            val fixture = fixture()
            joinWithQueue(fixture)
            fixture.player.emit(PlayerEvent.Ready)
            runCurrent()
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Ready),
            )
            runCurrent()
            fixture.player.resetCalls()

            // Nothing armed while the group was playing may start playback once it's paused.
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS - 1)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS * 2)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
        }

    // The disarm-during-armed-window race: a disarm must reach the running body ---------------------
    //
    // These two drive SyncPlayRecoveryNets directly, with the main-thread hop held on its own
    // scheduler, because the race lives *inside* one net firing: if the body nulled its own handle
    // at wake-up, a cancel arriving while it was suspended on the player probe (or the fallback's
    // seek-and-play hop) would be a no-op against an orphaned job, and the net would act for a
    // group state that had just changed.

    @Test
    fun `a pause net disarmed during its player probe neither asks nor pauses`() =
        runTest {
            val heldMain = HeldMainDispatcher()
            val player = FakePlayerHandle()
            player.snapshot = PlaybackSnapshot(isPlaying = true)
            val driver =
                RecordingNetsDriver(
                    backgroundScope,
                    SyncPlayState.InGroup(group(), null, SyncPlayGroupState.Paused, SyncPlayPhase.Paused),
                )
            val nets =
                SyncPlayRecoveryNets(
                    player,
                    timeSyncWithOffset(VirtualClock(testScheduler, origin), offsetMillis = 0L),
                    heldMain,
                    driver,
                )

            nets.armPauseNet()
            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS)
            runCurrent()
            // The elicit stage is now suspended on the main-thread player probe: disarm mid-probe.
            driver.pauseRequests shouldBe 0

            nets.cancelPauseNet()
            heldMain.drain()
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()
            heldMain.drain()
            runCurrent()

            driver.pauseRequests shouldBe 0
            player.pauseCount shouldBe 0
        }

    @Test
    fun `a self-sync disarmed during its seek-and-play hop starts nothing`() =
        runTest {
            val heldMain = HeldMainDispatcher()
            val player = FakePlayerHandle()
            val driver =
                RecordingNetsDriver(
                    backgroundScope,
                    SyncPlayState.InGroup(group(), null, SyncPlayGroupState.Playing, SyncPlayPhase.Waiting),
                )
            val nets =
                SyncPlayRecoveryNets(
                    player,
                    timeSyncWithOffset(VirtualClock(testScheduler, origin), offsetMillis = 0L),
                    heldMain,
                    driver,
                )
            nets.groupPlayingAnchor = SyncPlayAnchor(60_000L, origin)

            nets.armSelfSync()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS)
            runCurrent()
            driver.unpauseRequests shouldBe 1

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()
            // The fallback is now suspended on its main-thread seek-and-play hop when the group
            // stops playing and disarms it — "must never start playback in a group that has
            // since stopped".
            nets.cancelSelfSync()
            heldMain.drain()
            runCurrent()

            player.playCount shouldBe 0
            player.seekedToMs.shouldBeEmpty()
            driver.selfSynced.shouldBeEmpty()
        }

    /**
     * A main dispatcher that holds every dispatched block until [drain] — what freezes the nets
     * mid-hop so a disarm can land inside the armed window.
     */
    private class HeldMainDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            queue += block
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** Records what the nets asked of the controller, with the state a test hands it. */
    private class RecordingNetsDriver(
        private val scope: CoroutineScope,
        private val current: SyncPlayState,
    ) : SyncPlayRecoveryNets.Driver {
        var unpauseRequests = 0
        var pauseRequests = 0
        val selfSynced = mutableListOf<SyncPlayAnchor>()

        override fun state(): SyncPlayState = current

        override fun hasHost(): Boolean = true

        override fun launchNet(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

        override fun requestUnpause() {
            unpauseRequests++
        }

        override fun requestPause() {
            pauseRequests++
        }

        override fun onSelfSynced(anchor: SyncPlayAnchor) {
            selfSynced += anchor
        }
    }
}
