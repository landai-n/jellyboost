package dev.jellyfinnative.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [formatDurationSeconds].
 *
 * The rounding is always up, never to the nearest unit — see the function's KDoc — so most of these
 * pin a ceiling-division boundary rather than a plain conversion.
 */
class DurationFormatTest {
    @Test
    fun `zero seconds is stated as zero, not left blank`() {
        formatDurationSeconds(0L) shouldBe "0 s"
    }

    @Test
    fun `a negative duration clamps to zero rather than going negative`() {
        formatDurationSeconds(-5L) shouldBe "0 s"
    }

    @Test
    fun `just under a minute stays in whole seconds`() {
        formatDurationSeconds(59L) shouldBe "59 s"
    }

    @Test
    fun `exactly one minute is one minute, not zero`() {
        formatDurationSeconds(60L) shouldBe "1 min"
    }

    @Test
    fun `one second past a minute rounds up to the next minute`() {
        // 61 s is 1.0166... min, which must never read as "1 min" — that would understate how much
        // longer it actually takes.
        formatDurationSeconds(61L) shouldBe "2 min"
    }

    @Test
    fun `just under an hour still rounds up to a whole number of minutes`() {
        // 3599 s is 59.983... min; the ceiling is 60, and the value is still under the 3600 s
        // threshold that would switch it into the hours branch.
        formatDurationSeconds(3599L) shouldBe "60 min"
    }

    @Test
    fun `exactly one hour has no leftover minutes to show`() {
        formatDurationSeconds(3600L) shouldBe "1 h"
    }

    @Test
    fun `an hour with an exact number of leftover minutes shows both`() {
        formatDurationSeconds(4_800L) shouldBe "1 h 20 min"
    }

    @Test
    fun `a leftover that ceils to a sixtieth minute carries into the next hour`() {
        // 7141 s is 1 h plus 3541 s of remainder; 3541 s is 59.0166... min, which ceils to 60 — a
        // sixtieth minute does not exist, so it becomes the next hour instead.
        formatDurationSeconds(7_141L) shouldBe "2 h"
    }

    @Test
    fun `large durations keep counting in hours and minutes`() {
        // 90_061 s is 25 h plus 61 s of remainder, which ceils to 2 min.
        formatDurationSeconds(90_061L) shouldBe "25 h 2 min"
    }
}
