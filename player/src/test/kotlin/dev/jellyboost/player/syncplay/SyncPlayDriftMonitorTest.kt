package dev.jellyboost.player.syncplay

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.FakePlayerHandle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Correcting too eagerly makes the player stutter for drift nobody can see; correcting too late
 * lets a member fall visibly behind. Both edges are pinned to the millisecond, because a threshold
 * that quietly moves is a threshold nobody will notice has moved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayDriftMonitorTest {
    private val origin = Instant.parse("2026-07-30T18:41:00Z")

    @Test
    fun `drift just inside the tolerance is left alone`() =
        runTest {
            val fixture = fixture()
            // One second on, expected position is 61 000 ms; put the player 1 999 ms behind that.
            advanceTimeBy(1_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 61_000 - 1_999, isPlaying = true)

            fixture.monitor.correctOnce(anchor()) shouldBe null
            fixture.player.seekedToMs shouldBe emptyList()
        }

    @Test
    fun `drift just outside the tolerance is corrected`() =
        runTest {
            val fixture = fixture()
            advanceTimeBy(1_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 61_000 - 2_001, isPlaying = true)

            fixture.monitor.correctOnce(anchor()) shouldBe 61_000L
            fixture.player.seekedToMs shouldBe listOf(61_000L)
        }

    @Test
    fun `running ahead of the group is corrected too`() =
        runTest {
            val fixture = fixture()
            advanceTimeBy(1_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 61_000 + 3_000, isPlaying = true)

            fixture.monitor.correctOnce(anchor()) shouldBe 61_000L
            fixture.player.seekedToMs shouldBe listOf(61_000L)
        }

    @Test
    fun `the expected position is measured on the server clock, not the device's`() =
        runTest {
            // The device clock runs 5 s behind the server's, so at device-origin the group is
            // already 5 s past the anchor.
            val fixture = fixture(serverOffsetMillis = 5_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000, isPlaying = true)

            fixture.monitor.correctOnce(anchor()) shouldBe 65_000L
        }

    @Test
    fun `an ended item is never dragged past its end`() =
        runTest {
            val fixture = fixture()
            advanceTimeBy(30_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000, isPlaying = true, hasEnded = true)

            fixture.monitor.correctOnce(anchor()) shouldBe null
            fixture.player.seekedToMs shouldBe emptyList()
        }

    @Test
    fun `a player paused outside the protocol is left where it stands`() =
        runTest {
            val fixture = fixture()
            // A phone call or headphone unplug pauses ExoPlayer directly (audio focus) without the
            // phase leaving `Playing`; correcting it would only fight the user's own pause.
            advanceTimeBy(30_000)
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 60_000, isPlaying = false)

            fixture.monitor.correctOnce(anchor()) shouldBe null
            fixture.player.seekedToMs shouldBe emptyList()
        }

    @Test
    fun `the monitor checks once a second until it is cancelled`() =
        runTest {
            val fixture = fixture()
            // A player that never advances: it falls a second further behind on every tick.
            fixture.player.snapshot = PlaybackSnapshot(positionMs = 0, isPlaying = true)
            val job = backgroundScope.launch { fixture.monitor.monitor(anchor()) }
            runCurrent()

            advanceTimeBy(1_000)
            runCurrent()
            fixture.player.seekedToMs shouldBe listOf(61_000L)

            // The two ticks after the correction are within tolerance (1 s, then 2 s); the third
            // is not — which is the loop still running three seconds later.
            advanceTimeBy(3_000)
            runCurrent()
            fixture.player.seekedToMs shouldBe listOf(61_000L, 64_000L)

            job.cancel()
            advanceTimeBy(10_000)
            runCurrent()
            fixture.player.seekedToMs shouldBe listOf(61_000L, 64_000L)
        }

    private fun anchor() = SyncPlayAnchor(positionMs = 60_000, at = origin)

    private class Fixture(
        val monitor: SyncPlayDriftMonitor,
        val player: FakePlayerHandle,
    )

    private fun TestScope.fixture(serverOffsetMillis: Long = 0L): Fixture {
        val clock = VirtualClock(testScheduler, origin)
        val timeSync = timeSyncWithOffset(clock, serverOffsetMillis)
        val player = FakePlayerHandle()
        return Fixture(
            SyncPlayDriftMonitor(player, timeSync, StandardTestDispatcher(testScheduler)),
            player,
        )
    }
}
