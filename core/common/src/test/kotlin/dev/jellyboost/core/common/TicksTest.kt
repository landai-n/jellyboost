package dev.jellyboost.core.common

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TicksTest {
    @Test
    fun `ticksToMillis divides by ten thousand`() {
        Ticks.ticksToMillis(10_000L) shouldBe 1L
        Ticks.ticksToMillis(25_000L) shouldBe 2L
    }

    @Test
    fun `millisToTicks multiplies by ten thousand`() {
        Ticks.millisToTicks(1L) shouldBe 10_000L
        Ticks.millisToTicks(0L) shouldBe 0L
    }

    @Test
    fun `millisToTicks and ticksToMillis round-trip on a whole millisecond`() {
        val millis = 123_456L
        Ticks.ticksToMillis(Ticks.millisToTicks(millis)) shouldBe millis
    }

    @Test
    fun `ticksToMinutes truncates a partial minute rather than rounding`() {
        // 90 s worth of ticks is 1.5 minutes; truncation reports 1, not 2.
        Ticks.ticksToMinutes(90L * Ticks.PER_MILLISECOND * 1000) shouldBe 1
    }

    @Test
    fun `ticksToMinutes on exactly one minute`() {
        Ticks.ticksToMinutes(Ticks.PER_MINUTE) shouldBe 1
    }

    @Test
    fun `positiveMillisOrNull converts a real runtime`() {
        Ticks.positiveMillisOrNull(600_000_000L) shouldBe 60_000L
    }

    @Test
    fun `positiveMillisOrNull is null for a null runtime`() {
        Ticks.positiveMillisOrNull(null).shouldBeNull()
    }

    @Test
    fun `positiveMillisOrNull is null for a zero runtime`() {
        Ticks.positiveMillisOrNull(0L).shouldBeNull()
    }

    @Test
    fun `positiveMillisOrNull is null when the ticks divide down to a non-positive millisecond count`() {
        // Fewer than 10,000 ticks (one millisecond) is a positive tick count that still divides to zero
        // milliseconds — the edge the guard exists for, since division happens before the positivity check.
        Ticks.positiveMillisOrNull(9_999L).shouldBeNull()
    }

    @Test
    fun `positiveMillisOrNull is null for a negative runtime`() {
        Ticks.positiveMillisOrNull(-10_000L).shouldBeNull()
    }
}
