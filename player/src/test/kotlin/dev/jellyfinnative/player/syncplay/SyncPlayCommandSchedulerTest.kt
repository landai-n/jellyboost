package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.millisToTicks
import dev.jellyfinnative.player.session.FakePlayerHandle
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommand
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommandType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Unit tests for [SyncPlayCommandScheduler].
 *
 * The scheduler is where a wrong answer is invisible until someone else in the room says "you're
 * ahead of me". Every test therefore fixes a server-clock offset and asserts on the *exact* virtual
 * instant the player was touched, not merely that it eventually was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayCommandSchedulerTest {
    private val origin = Instant.parse("2026-07-30T18:41:00Z")
    private val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @Test
    fun `an unpause is applied at the local instant matching the server's, not on arrival`() =
        runTest {
            val fixture = fixture(serverOffsetMillis = 2_000)
            // Server says "unpause at server-18:41:03", and the server clock runs 2 s ahead — so
            // this device must act at device-18:41:01, one second from now.
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 3_000, positionMs = 0))

            advanceTimeBy(999)
            runCurrent()
            fixture.player.playCount shouldBe 0

            advanceTimeBy(1)
            runCurrent()
            fixture.player.playCount shouldBe 1
            currentTime shouldBe 1_000
        }

    @Test
    fun `an unpause already on the anchor does not seek`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000)

            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 1_000, positionMs = 60_000))
            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.seekedToMs shouldBe emptyList()
            fixture.player.playCount shouldBe 1
        }

    @Test
    fun `an unpause off the anchor seeks to it first`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 12_000)

            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 1_000, positionMs = 60_000))
            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.seekedToMs shouldBe listOf(60_000L)
            fixture.player.playCount shouldBe 1
        }

    @Test
    fun `a past-due unpause catches up by however long the group has been playing without us`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000)
            // The command was due 4 s ago (its instant is 4 s before the clock's origin).
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = -4_000, positionMs = 60_000))
            runCurrent()

            // 60 s at the anchor, plus the 4 s the group played while we were not listening.
            fixture.player.seekedToMs shouldBe listOf(64_000L)
            fixture.player.playCount shouldBe 1
            currentTime shouldBe 0
        }

    @Test
    fun `a pause seeks to the position the group parked at, then pauses`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 61_500, isPlaying = true)

            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 500, positionMs = 60_000))
            advanceTimeBy(500)
            runCurrent()

            fixture.player.seekedToMs shouldBe listOf(60_000L)
            fixture.player.pauseCount shouldBe 1
            fixture.player.playCount shouldBe 0
        }

    @Test
    fun `a seek repositions without starting playback`() =
        runTest {
            val fixture = fixture()

            fixture.scheduler.schedule(command(SyncPlayCommandType.Seek, atMillis = 500, positionMs = 90_000))
            advanceTimeBy(500)
            runCurrent()

            fixture.player.seekedToMs shouldBe listOf(90_000L)
            fixture.player.playCount shouldBe 0
            fixture.player.pauseCount shouldBe 0
        }

    @Test
    fun `a stop stops the player`() =
        runTest {
            val fixture = fixture()

            fixture.scheduler.schedule(command(SyncPlayCommandType.Stop, atMillis = 500, positionMs = null))
            advanceTimeBy(500)
            runCurrent()

            fixture.player.stopped shouldBe true
        }

    @Test
    fun `a new command replaces the pending one - the superseded command never fires`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 5_000, positionMs = 0))
            advanceTimeBy(1_000)
            runCurrent()

            // The user pauses before the unpause was due; the group's pause overtakes it.
            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 2_000, positionMs = 30_000))
            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.playCount shouldBe 0
            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(30_000L)
        }

    @Test
    fun `a cancelled schedule applies nothing`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 5_000, positionMs = 0))

            fixture.scheduler.cancel()
            advanceTimeBy(10_000)
            runCurrent()

            fixture.player.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `an applied unpause publishes the anchor the drift monitor needs`() =
        runTest {
            val fixture = fixture()
            val applied = mutableListOf<SyncPlayAppliedCommand>()
            backgroundScope.launch { fixture.scheduler.applied.collect { applied += it } }
            runCurrent()

            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 1_000, positionMs = 60_000))
            advanceTimeBy(1_000)
            runCurrent()

            applied.single().anchor shouldBe SyncPlayAnchor(60_000L, origin.plusMillis(1_000))
        }

    @Test
    fun `an applied pause publishes no anchor - nothing is advancing to measure against`() =
        runTest {
            val fixture = fixture()
            val applied = mutableListOf<SyncPlayAppliedCommand>()
            backgroundScope.launch { fixture.scheduler.applied.collect { applied += it } }
            runCurrent()

            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 1_000, positionMs = 60_000))
            advanceTimeBy(1_000)
            runCurrent()

            applied.single().anchor shouldBe null
        }

    // Applied exactly once (M11 fix batch, B1/B2) ---------------------------------------------------

    @Test
    fun `the same command sent again is applied once`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 500, positionMs = 60_000))
            advanceTimeBy(500)
            runCurrent()

            fixture.player.pauseCount shouldBe 1

            // The server's own "client got lost, sending current state" re-send: same instant, same
            // position, same slot, only a fresh emission stamp.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Pause, atMillis = 500, positionMs = 60_000, emittedAtMillis = 1_000),
            )
            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(60_000L)
        }

    @Test
    fun `a past-due unpause is applied once however often it is re-sent`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000)
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = -4_000, positionMs = 60_000))
            runCurrent()

            fixture.player.playCount shouldBe 1

            // What the device saw: the same past-due instant coming back about once a second, each
            // arrival seeking further forward than the last.
            repeat(REPEATS) { index ->
                fixture.scheduler.schedule(
                    command(
                        SyncPlayCommandType.Unpause,
                        atMillis = -4_000,
                        positionMs = 60_000,
                        emittedAtMillis = (index + 1) * 1_000L,
                    ),
                )
                advanceTimeBy(1_000)
                runCurrent()
            }

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(64_000L)
        }

    @Test
    fun `a command emitted before the one already applied is ignored`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Pause, atMillis = 0, positionMs = 30_000, emittedAtMillis = 2_000),
            )
            runCurrent()

            fixture.player.pauseCount shouldBe 1

            // A straggler from before it — a socket reconnect replaying what it had queued. The
            // group's timeline only ever moves forwards.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Unpause, atMillis = 0, positionMs = 10_000, emittedAtMillis = 1_000),
            )
            advanceTimeBy(1_000)
            runCurrent()

            fixture.player.playCount shouldBe 0
            fixture.player.seekedToMs shouldBe listOf(30_000L)
        }

    // Only what was *applied* is remembered (M11 fix batch, the lost pause/resume echo) -------------

    @Test
    fun `a command superseded before it applied is not remembered, so it can still arrive`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 5_000, positionMs = 0))
            // Overtaken while still waiting: neither command has touched the player yet.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Pause, atMillis = 6_000, positionMs = 30_000, emittedAtMillis = 1_000),
            )
            runCurrent()
            fixture.player.hadNoTransportCalls shouldBe true

            // The group's unpause comes round again. Nothing ever applied it, so nothing may drop it.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Unpause, atMillis = 5_000, positionMs = 0, emittedAtMillis = 2_000),
            )
            advanceTimeBy(5_000)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.pauseCount shouldBe 0
            currentTime shouldBe 5_000
        }

    @Test
    fun `the server's re-send of a command that never applied is applied`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 5_000, positionMs = 60_000))
            // A seek overtakes the pause and is the one that actually reaches the player.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Seek, atMillis = 200, positionMs = 90_000, emittedAtMillis = 100),
            )
            advanceTimeBy(200)
            runCurrent()
            fixture.player.seekedToMs shouldBe listOf(90_000L)

            // "Client got lost, sending current state" — the pause verbatim, freshly stamped. It is
            // the recovery path, and dropping it would leave this member playing on alone.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Pause, atMillis = 5_000, positionMs = 60_000, emittedAtMillis = 1_000),
            )
            advanceTimeBy(4_800)
            runCurrent()

            fixture.player.pauseCount shouldBe 1
            fixture.player.seekedToMs shouldBe listOf(90_000L, 60_000L)
        }

    @Test
    fun `the re-send of a command that did apply is still ignored`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000)
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 500, positionMs = 60_000))
            advanceTimeBy(500)
            runCurrent()

            fixture.player.playCount shouldBe 1

            // The storm guard: this one *did* reach the player, so acting again would only re-seek,
            // re-buffer, report ready, and earn the next repeat.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Unpause, atMillis = 500, positionMs = 60_000, emittedAtMillis = 1_000),
            )
            advanceTimeBy(5_000)
            runCurrent()

            fixture.player.playCount shouldBe 1
            fixture.player.seekedToMs shouldBe emptyList()
        }

    @Test
    fun `forgetting the applied memory lets the server's verbatim re-send land on a rebuilt player`() =
        runTest {
            val fixture = fixture()
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 0)
            fixture.scheduler.schedule(command(SyncPlayCommandType.Unpause, atMillis = 0, positionMs = 0))
            runCurrent()
            fixture.player.playCount shouldBe 1

            // The player is rebuilt (a track change): what was applied no longer describes it. The
            // controller forgets on the rebuild, and the server answers the rebuild's `ready` with
            // the standing command verbatim — same instant, same position, fresh `emittedAt`.
            // Without the forget this was "Ignoring a repeated SyncPlay Unpause" on device
            // (2026-07-31), and the member never resumed.
            fixture.scheduler.forgetApplied()
            advanceTimeBy(2_000)
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Unpause, atMillis = 0, positionMs = 0, emittedAtMillis = 2_000),
            )
            runCurrent()

            fixture.player.playCount shouldBe 2
            // Past due by the rebuild's two seconds: the ordinary catch-up puts the player at the
            // group's real position, not the anchor it was parked on.
            fixture.player.seekedToMs shouldBe listOf(2_000L)
        }

    @Test
    fun `a stale straggler cannot displace a command still waiting to fire`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Pause, atMillis = 3_000, positionMs = 60_000, emittedAtMillis = 2_000),
            )

            // Emitted before the pending pause — cancelling it would lose the group's newest word.
            fixture.scheduler.schedule(
                command(SyncPlayCommandType.Seek, atMillis = 500, positionMs = 10_000, emittedAtMillis = 1_000),
            )
            advanceTimeBy(3_000)
            runCurrent()

            fixture.player.seekedToMs shouldBe listOf(60_000L)
            fixture.player.pauseCount shouldBe 1
        }

    @Test
    fun `cancelling forgets what was applied, so a re-attached player can be told again`() =
        runTest {
            val fixture = fixture()
            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 0, positionMs = 60_000))
            runCurrent()
            fixture.scheduler.cancel()

            fixture.scheduler.schedule(command(SyncPlayCommandType.Pause, atMillis = 0, positionMs = 60_000))
            runCurrent()

            fixture.player.pauseCount shouldBe 2
        }

    private fun command(
        type: SyncPlayCommandType,
        atMillis: Long,
        positionMs: Long?,
        emittedAtMillis: Long = 0L,
    ) = SyncPlayCommand(
        type = type,
        whenInstant = origin.plusMillis(atMillis),
        positionTicks = positionMs?.millisToTicks(),
        playlistItemId = playlistItemId,
        emittedAt = origin.plusMillis(emittedAtMillis),
    )

    private class Fixture(
        val scheduler: SyncPlayCommandScheduler,
        val player: FakePlayerHandle,
    )

    /**
     * @param serverOffsetMillis how far the *server's* clock runs ahead of this device's. The
     *   command instants in these tests are always server instants, so a non-zero offset is what
     *   proves the conversion happens at all.
     */
    private fun TestScope.fixture(serverOffsetMillis: Long = 0L): Fixture {
        val clock = VirtualClock(testScheduler, origin)
        val timeSync = timeSyncWithOffset(clock, serverOffsetMillis)
        val player = FakePlayerHandle()
        return Fixture(
            SyncPlayCommandScheduler(
                playerHandle = player,
                timeSync = timeSync,
                clock = clock,
                scope = backgroundScope,
                mainDispatcher = StandardTestDispatcher(testScheduler),
            ),
            player,
        )
    }

    private companion object {
        /** Re-sends of one past-due command; enough to show the count does not move with them. */
        const val REPEATS = 5
    }
}
