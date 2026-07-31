package dev.jellyfinnative.player.syncplay.time

import dev.jellyfinnative.player.syncplay.FakeSyncPlayApi
import dev.jellyfinnative.player.syncplay.SyncPlayCall
import dev.jellyfinnative.player.syncplay.VirtualClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for [SyncPlayPinger].
 *
 * The cadence is the point: the burst right after joining is what makes the *first* scheduled
 * command land accurately, and the slow cadence afterwards is what keeps the loop off the battery.
 * Both are asserted against virtual time so a changed constant fails here rather than in a room
 * full of people watching a film.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayPingerTest {
    private val origin = Instant.parse("2026-07-30T18:41:00Z")

    @Test
    fun `three samples a second apart, then one every five seconds`() =
        runTest {
            val clock = VirtualClock(testScheduler, origin)
            val api = FakeSyncPlayApi(clock)
            val pinger = SyncPlayPinger(api, SyncPlayTimeSync(clock))

            backgroundScope.launch { pinger.run() }
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 1

            advanceTimeBy(1_000)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 2

            advanceTimeBy(1_000)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 3

            // The burst is over: nothing more until five seconds have passed.
            advanceTimeBy(4_999)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 3

            advanceTimeBy(1)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 4
        }

    @Test
    fun `each exchange feeds the estimator and reports half the round trip`() =
        runTest {
            val clock = VirtualClock(testScheduler, origin)
            val api = FakeSyncPlayApi(clock)
            api.serverOffsetMillis = 2_500
            api.roundTripMillis = 120
            val timeSync = SyncPlayTimeSync(clock)
            val pinger = SyncPlayPinger(api, timeSync)

            backgroundScope.launch { pinger.run() }
            runCurrent()

            timeSync.offset.value shouldBe Duration.ofMillis(2_500)
            api.callsOf<SyncPlayCall.ReportPing>().single().pingMillis shouldBe 60
        }

    @Test
    fun `a poke takes the next sample at once instead of at the end of the wait`() =
        runTest {
            val clock = VirtualClock(testScheduler, origin)
            val api = FakeSyncPlayApi(clock)
            val pinger = SyncPlayPinger(api, SyncPlayTimeSync(clock))

            backgroundScope.launch { pinger.run() }
            runCurrent()
            // Past the burst, so the loop is sitting on the five-second wait the poke exists to cut.
            advanceTimeBy(2_001)
            runCurrent()
            val taken = api.callsOf<SyncPlayCall.ReportPing>().size

            pinger.sampleNow()
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe taken + 1

            // And the cadence carries on from there rather than firing twice.
            advanceTimeBy(4_999)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe taken + 1
        }

    @Test
    fun `a poke with no loop running does not make the next group's first cadence wrong`() =
        runTest {
            val clock = VirtualClock(testScheduler, origin)
            val api = FakeSyncPlayApi(clock)
            val pinger = SyncPlayPinger(api, SyncPlayTimeSync(clock))

            pinger.sampleNow()
            backgroundScope.launch { pinger.run() }
            runCurrent()

            // The stale poke was dropped: one sample for the start of the loop, not two.
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 1
            advanceTimeBy(999)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 1
        }

    @Test
    fun `a failed exchange costs its own sample and nothing more`() =
        runTest {
            val clock = VirtualClock(testScheduler, origin)
            val api = FakeSyncPlayApi(clock)
            api.failNextSample = IllegalStateException("socket timed out")
            val pinger = SyncPlayPinger(api, SyncPlayTimeSync(clock))

            backgroundScope.launch { pinger.run() }
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 0

            // The loop is still running a second later, which is the whole point: one timed-out
            // request must not leave the client scheduling for ever against a stale clock.
            advanceTimeBy(1_000)
            runCurrent()
            api.callsOf<SyncPlayCall.ReportPing>().size shouldBe 1
        }
}
