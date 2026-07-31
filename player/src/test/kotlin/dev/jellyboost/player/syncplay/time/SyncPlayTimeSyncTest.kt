package dev.jellyboost.player.syncplay.time

import dev.jellyboost.player.syncplay.model.TimeSyncSample
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [SyncPlayTimeSync].
 *
 * This is the piece nothing else can compensate for: SyncPlay schedules commands on the *server's*
 * clock, so a wrong offset does not degrade sync, it destroys it — the group plays and this device
 * plays at some other moment entirely. Every test here builds its samples from an explicit "true"
 * offset and network delay, so a broken formula shows up as a number, not as a vague drift.
 */
class SyncPlayTimeSyncTest {
    private val now = Instant.parse("2026-07-30T18:41:03Z")
    private val timeSync = SyncPlayTimeSync(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `no samples means no assumed offset`() {
        timeSync.offset.value shouldBe Duration.ZERO
        timeSync.serverNow() shouldBe now
    }

    @Test
    fun `the very first sample takes effect immediately, however slow the exchange was`() {
        // A single sample cannot be its own outlier — the median is its own RTT. Joining a group on
        // a bad connection must still start in sync rather than at a zero offset.
        timeSync.record(sample(offsetMillis = 3_000, rttMillis = 10_000))

        timeSync.offset.value shouldBe Duration.ofSeconds(3)
    }

    @Test
    fun `a server clock running ahead gives a positive offset`() {
        timeSync.record(sample(offsetMillis = 2_500, rttMillis = 120))

        timeSync.offset.value shouldBe Duration.ofMillis(2_500)
        timeSync.serverNow() shouldBe now.plusMillis(2_500)
        timeSync.toLocalTime(now.plusMillis(2_500)) shouldBe now
        timeSync.toServerTime(now) shouldBe now.plusMillis(2_500)
    }

    @Test
    fun `a server clock running behind gives a negative offset`() {
        timeSync.record(sample(offsetMillis = -1_500, rttMillis = 120))

        timeSync.offset.value shouldBe Duration.ofMillis(-1_500)
        timeSync.serverNow() shouldBe now.minusMillis(1_500)
    }

    @Test
    fun `the server's own processing time is not counted as network delay`() {
        // t2 - t1 is the server thinking, not the wire; leaving it in would inflate the RTT and
        // eventually get healthy samples thrown away as outliers.
        val sample = sample(offsetMillis = 0, rttMillis = 200, serverProcessingMillis = 900)

        sample.roundTrip shouldBe Duration.ofMillis(200)
        timeSync.record(sample) shouldBe Duration.ZERO
    }

    @Test
    fun `asymmetric round trips skew the estimate by half the asymmetry`() {
        // 300 ms up, 100 ms down, clocks actually identical: NTP's blind spot, and the reason the
        // drift monitor exists. The number is pinned so a formula change is visible.
        timeSync.record(sample(offsetMillis = 0, rttMillis = 400, upMillis = 300))

        timeSync.offset.value shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `a stalled exchange is dropped instead of poisoning the estimate`() {
        repeat(SyncPlayTimeSync.WINDOW_SIZE - 1) { timeSync.record(sample(offsetMillis = 0, rttMillis = 100)) }

        // Median RTT is 100 ms, so the threshold is the 1 s floor; 5 s is well past it.
        timeSync.record(sample(offsetMillis = 10_000, rttMillis = 5_000))

        timeSync.offset.value shouldBe Duration.ZERO
    }

    @Test
    fun `a sample exactly on the outlier threshold is kept`() {
        // Three 400 ms samples then one at 1200 ms: median 400, threshold max(1 s, 3x400) = 1200 ms.
        repeat(3) { timeSync.record(sample(offsetMillis = 0, rttMillis = 400)) }

        timeSync.record(sample(offsetMillis = 800, rttMillis = 1_200))

        // Retained, so it pulls the mean of four samples: (0 + 0 + 0 + 800) / 4.
        timeSync.offset.value shouldBe Duration.ofMillis(200)
    }

    @Test
    fun `one millisecond past the threshold is dropped`() {
        repeat(3) { timeSync.record(sample(offsetMillis = 0, rttMillis = 400)) }

        timeSync.record(sample(offsetMillis = 800, rttMillis = 1_201))

        timeSync.offset.value shouldBe Duration.ZERO
    }

    @Test
    fun `the window holds eight samples and evicts the oldest`() {
        timeSync.record(sample(offsetMillis = 8_000, rttMillis = 100))
        repeat(SyncPlayTimeSync.WINDOW_SIZE - 1) { timeSync.record(sample(offsetMillis = 0, rttMillis = 100)) }

        // Still in the window: its 8 s pulls the mean of eight samples up by a second.
        timeSync.sampleCount shouldBe SyncPlayTimeSync.WINDOW_SIZE
        timeSync.offset.value shouldBe Duration.ofSeconds(1)

        // The ninth sample pushes the first one out.
        timeSync.record(sample(offsetMillis = 0, rttMillis = 100))

        timeSync.sampleCount shouldBe SyncPlayTimeSync.WINDOW_SIZE
        timeSync.offset.value shouldBe Duration.ZERO
    }

    @Test
    fun `a clock that is genuinely adjusted is followed, not averaged forever`() {
        repeat(SyncPlayTimeSync.WINDOW_SIZE) { timeSync.record(sample(offsetMillis = 0, rttMillis = 100)) }

        repeat(SyncPlayTimeSync.WINDOW_SIZE) { timeSync.record(sample(offsetMillis = 4_000, rttMillis = 100)) }

        timeSync.offset.value shouldBe Duration.ofSeconds(4)
    }

    @Test
    fun `reset forgets the server this offset belonged to`() {
        timeSync.record(sample(offsetMillis = 2_500, rttMillis = 120))

        timeSync.reset()

        timeSync.sampleCount shouldBe 0
        timeSync.offset.value shouldBe Duration.ZERO
        timeSync.serverNow() shouldBe now
    }

    /**
     * Builds the four timestamps of one exchange from the physical quantities they encode.
     *
     * @param offsetMillis how far the server clock is ahead of the device's (negative = behind).
     * @param rttMillis network round-trip, excluding the server's processing time.
     * @param upMillis how much of [rttMillis] was the request leg; the rest is the response leg.
     * @param serverProcessingMillis time between the server receiving and answering.
     */
    private fun sample(
        offsetMillis: Long,
        rttMillis: Long,
        upMillis: Long = rttMillis / 2,
        serverProcessingMillis: Long = 0,
    ): TimeSyncSample {
        val requestSent = now
        val serverReceived = requestSent.plusMillis(upMillis + offsetMillis)
        val serverSent = serverReceived.plusMillis(serverProcessingMillis)
        val responseReceived = requestSent.plusMillis(rttMillis + serverProcessingMillis)
        return TimeSyncSample(
            requestSent = requestSent,
            serverReceived = serverReceived,
            serverSent = serverSent,
            responseReceived = responseReceived,
        )
    }
}
