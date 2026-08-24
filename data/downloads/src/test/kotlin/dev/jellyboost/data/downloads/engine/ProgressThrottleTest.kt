package dev.jellyboost.data.downloads.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ProgressThrottle] — throttled Room writes, at 500 ms or 1 %.
 *
 * Both halves of that `or` earn their place, and each has a failure mode worth a test: without the
 * time bound a slow transfer looks frozen, and without the percentage bound a fast one skips from
 * 0 % to 40 % between samples.
 */
class ProgressThrottleTest {
    private val throttle = ProgressThrottle()

    @Test
    fun `the very first sample is always written`() {
        // It is what turns the row from "queued" into a live number.
        throttle.shouldWrite(bytesDownloaded = 64_000, bytesTotal = 1_000_000, now = 0L) shouldBe true
    }

    @Test
    fun `a sample just after a write is dropped`() {
        throttle.recordWrite(bytesDownloaded = 0L, now = 0L)

        throttle.shouldWrite(bytesDownloaded = 64_000, bytesTotal = 100_000_000, now = 100L) shouldBe false
    }

    @Test
    fun `half a second is enough on its own`() {
        throttle.recordWrite(bytesDownloaded = 0L, now = 0L)

        // Barely any bytes moved, but a stalled-looking bar is worse than a cheap write.
        throttle.shouldWrite(bytesDownloaded = 1_000L, bytesTotal = 100_000_000, now = 500L) shouldBe true
    }

    @Test
    fun `one percent is enough on its own`() {
        throttle.recordWrite(bytesDownloaded = 0L, now = 0L)

        throttle.shouldWrite(bytesDownloaded = 1_000_000L, bytesTotal = 100_000_000L, now = 10L) shouldBe true
    }

    @Test
    fun `just under one percent within the interval is dropped`() {
        throttle.recordWrite(bytesDownloaded = 0L, now = 0L)

        throttle.shouldWrite(bytesDownloaded = 999_999L, bytesTotal = 100_000_000L, now = 10L) shouldBe false
    }

    @Test
    fun `the percentage is measured from the last write, not from zero`() {
        throttle.recordWrite(bytesDownloaded = 50_000_000L, now = 0L)

        throttle.shouldWrite(bytesDownloaded = 50_500_000L, bytesTotal = 100_000_000L, now = 10L) shouldBe false
        throttle.shouldWrite(bytesDownloaded = 51_000_000L, bytesTotal = 100_000_000L, now = 10L) shouldBe true
    }

    @Test
    fun `an unknown total falls back to the time bound alone`() {
        // A chunked response declares no length; dividing by it would be a crash or a false 100 %.
        throttle.recordWrite(bytesDownloaded = 0L, now = 0L)

        throttle.shouldWrite(bytesDownloaded = 10_000_000L, bytesTotal = 0L, now = 100L) shouldBe false
        throttle.shouldWrite(bytesDownloaded = 10_000_000L, bytesTotal = 0L, now = 600L) shouldBe true
    }

    @Test
    fun `a custom interval is honoured`() {
        val slow = ProgressThrottle(intervalMillis = 2_000L, fraction = 1f)
        slow.recordWrite(bytesDownloaded = 0L, now = 0L)

        slow.shouldWrite(bytesDownloaded = 1_000L, bytesTotal = 100_000L, now = 1_999L) shouldBe false
        slow.shouldWrite(bytesDownloaded = 1_000L, bytesTotal = 100_000L, now = 2_000L) shouldBe true
    }
}
