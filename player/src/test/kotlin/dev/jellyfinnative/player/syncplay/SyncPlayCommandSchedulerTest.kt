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

    private fun command(
        type: SyncPlayCommandType,
        atMillis: Long,
        positionMs: Long?,
    ) = SyncPlayCommand(
        type = type,
        whenInstant = origin.plusMillis(atMillis),
        positionTicks = positionMs?.millisToTicks(),
        playlistItemId = playlistItemId,
        emittedAt = origin,
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
}
