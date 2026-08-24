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
 * The B3 timer safety nets — the behaviour `SyncPlayRecoveryNets` owns.
 *
 * Exercised through the real controller (see [SyncPlayControllerTestBase]): every scenario here
 * predates the extraction and pins the same observable protocol behaviour it always did.
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

            // The item ends, the group advances and reports itself playing — and then goes quiet.
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

            // Stage one asks rather than acts, and the server answers a request for the state it is
            // already in with the authoritative command ("client got lost").
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // Nothing came back, so the local fallback lands — today's behaviour, one window later.
            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            (fixture.controller.state.value as SyncPlayState.InGroup)
                .phase
                .shouldBeInstanceOf<SyncPlayPhase.Playing>()
            // Recovering must not re-open the handshake: another ready is what the storm is made of.
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            // And the second window is a fallback, not another ask: one request per episode.
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

            // A request for the state the group is already in is not a state change: the server
            // reads it as "client got lost" and re-sends the current command to this session alone
            // (`PlayingGroupState.HandleRequest(UnpauseGroupRequest)`, prevState == Playing).
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            val whenInstant = now()
            fixture.socket.emit(command(SyncPlayCommandType.Unpause, whenInstant, positionMs = 90_000))
            runCurrent()

            // The group's own timeline, exact — not the inferred anchor the fallback would have used.
            fixture.player.playCount shouldBe 1
            (fixture.controller.state.value as SyncPlayState.InGroup).phase shouldBe
                SyncPlayPhase.Playing(SyncPlayAnchor(90_000L, whenInstant))

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()

            // The applied command stood the second window down: no local self-sync behind it, and
            // nothing asked for twice.
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

            // `PausedGroupState.HandleRequest(PauseGroupRequest)` with prevState == Paused answers
            // the same way, and its command carries the position the group is parked at.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.pauseCount shouldBe 0

            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()

            // Parked where the group is, rather than merely stopped where this member happened to be.
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

            // The group changed its mind while the elicited command was still owed. The re-armed net
            // is the same job, so the ordinary disarm reaches it.
            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS - 1)
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // Nothing started a player in a group that is now paused, and the paused group's own net
            // has a stopped player to look at, so it asks for nothing either.
            fixture.player.hadNoTransportCalls shouldBe true
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>().size shouldBe 1
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `a self-sync measures from the instant the group's position was true, not from now`() =
        runTest {
            val fixture = fixture()
            // The group published "60 s" twenty seconds ago and has been playing ever since, so it
            // is at 80 s now. Pairing that position with *this* moment is the browser-resume desync
            // in the bug report: the member lands twenty seconds short and the drift monitor, handed
            // the same anchor, then defends the short timeline instead of closing the gap.
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
            // Both windows: the server is asked to repeat itself first, and only a repeat that never
            // comes leaves the inferred anchor to do the work.
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe
                listOf(
                    60_000L + GROUP_HEAD_START_MS +
                        SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS,
                )
            // The anchor handed on to the drift monitor is the queue's own instant, so every later
            // correction is measured against the group's real timeline.
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
            // Neither stage runs: a member playing in step has nothing to ask the server to repeat.
            fixture.api.callsOf<SyncPlayCall.RequestUnpause>() shouldBe emptyList()
        }

    @Test
    fun `a resume out of a pause anchors on the parked player, not the stale queue`() =
        runTest {
            val fixture = fixture()
            joinPlaying(fixture)
            // The group pauses at 90 s — the command parks this member exactly there.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Paused, SyncPlayRequestKind.Pause),
            )
            fixture.socket.emit(command(SyncPlayCommandType.Pause, now(), positionMs = 90_000))
            runCurrent()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 90_000, isPlaying = false)
            fixture.player.resetCalls()

            // Parked for a minute: the queue's own reading (0 s, published at join) is long stale.
            advanceTimeBy(60_000)
            // The resume's command frame is lost; only the state update arrives.
            fixture.socket.emit(
                SyncPlayGroupEvent.StateChanged(SyncPlayGroupState.Playing, SyncPlayRequestKind.Unpause),
            )
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.SELF_SYNC_TIMEOUT_MS + SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            fixture.player.playCount shouldBe 1
            // The group froze where this member is parked, so the fallback resumes from the parked
            // position plus its own delay — not from the queue's position plus a minute of elapsed
            // wall clock, which is the 23-minute jump measured on device (run 3).
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

            // Nothing local yet: the group's own `SendCommand` is still what is supposed to do this.
            fixture.player.hadNoTransportCalls shouldBe true
            val inGroup = fixture.controller.state.value as SyncPlayState.InGroup
            inGroup.groupState shouldBe SyncPlayGroupState.Paused
            inGroup.phase shouldBe SyncPlayPhase.Paused

            advanceTimeBy(SyncPlayRecoveryNets.PAUSE_NET_TIMEOUT_MS)
            runCurrent()

            // Stage one: a redundant pause request, which a group that is already paused answers by
            // re-sending its own pause to this session. Still nothing local.
            fixture.api.callsOf<SyncPlayCall.RequestPause>().size shouldBe 1
            fixture.player.hadNoTransportCalls shouldBe true

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()

            // The command never came, and a member playing on alone is the one failure the phase
            // cannot even see: `Paused` shuts the drift monitor off with it.
            fixture.player.pauseCount shouldBe 1
            // The net only ever pauses — no seek, no `play`, and nothing reported to the server.
            fixture.player.seekedToMs shouldBe emptyList()
            fixture.player.playCount shouldBe 0
            fixture.api.callsOf<SyncPlayCall.ReportReady>() shouldBe emptyList()
            // The fallback window asks nothing: one elicit per state-change episode.
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

            // Once, by the command the group sent — the net armed behind it must not pause again.
            fixture.player.pauseCount shouldBe 1
            // An applied command stands both stages down, so nothing is asked for either.
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
            // Nothing to recover, so nothing is asked for either: a stopped player is already where
            // the group is, and a request per lost pause would be traffic for its own sake.
            fixture.api.callsOf<SyncPlayCall.RequestPause>() shouldBe emptyList()
        }

    @Test
    fun `joining a group that is already paused stops a player that was running solo`() =
        runTest {
            val fixture = fixture()
            // The user was watching this alone, and joins a group that is sitting paused.
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

            // The group changed its mind inside the net's window; nothing armed while it was playing
            // may start playback in a group that is now paused.
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
            // The elicit stage is now suspended on the main-thread player probe.
            driver.pauseRequests shouldBe 0

            // The group's own command lands and the controller disarms - mid-probe.
            nets.cancelPauseNet()
            heldMain.drain()
            runCurrent()
            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS * 2)
            runCurrent()
            heldMain.drain()
            runCurrent()

            // Neither stage fired: no redundant ask, and no local pause behind the disarm.
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
            // The elicit stage asked once and re-armed as the fallback window.
            driver.unpauseRequests shouldBe 1

            advanceTimeBy(SyncPlayRecoveryNets.COMMAND_REPEAT_TIMEOUT_MS)
            runCurrent()
            // The fallback is now suspended on its main-thread seek-and-play hop; the group
            // stops playing and the controller disarms - exactly the "must never start playback
            // in a group that has since stopped" rule.
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
