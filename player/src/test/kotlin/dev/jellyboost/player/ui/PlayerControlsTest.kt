package dev.jellyboost.player.ui

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [showSheetButtonLabels] — the one decision behind the bottom bar's labelled and
 * icon-only pickers, kept out of the composable so the threshold can be checked without a device.
 */
class PlayerControlsTest {
    @Test
    fun `a phone in landscape gets icon-only pickers`() {
        // The sweep's worst case: five pickers and the clock had near-zero slack at this width.
        showSheetButtonLabels(800.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `a tablet in portrait gets icon-only pickers`() {
        showSheetButtonLabels(711.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `the threshold itself is labelled`() {
        showSheetButtonLabels(840.dp, fontScale = 1f) shouldBe true
    }

    @Test
    fun `the capped tablet-landscape bar keeps its labels`() {
        // The bar is capped at 1000dp, so this is what the tablet renders — it must not change.
        showSheetButtonLabels(1000.dp, fontScale = 1f) shouldBe true
    }

    @Test
    fun `a hand-held width well under the threshold is icon-only`() {
        showSheetButtonLabels(640.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `the tablet bar drops its labels once the text is scaled up`() {
        // Same 1000dp bar, 1.5x text: the words are half again as wide, so the row that fitted them
        // at 12sp no longer does and the pickers go icon-only rather than clipping (A11Y-P-10).
        showSheetButtonLabels(1000.dp, fontScale = 1.5f) shouldBe false
    }

    @Test
    fun `a wide enough bar keeps its labels even at a scaled-up text size`() {
        // 840 * 1.5 = 1260: the threshold moves with the text rather than switching everything off.
        showSheetButtonLabels(1260.dp, fontScale = 1.5f) shouldBe true
        showSheetButtonLabels(1259.dp, fontScale = 1.5f) shouldBe false
    }

    @Test
    fun `the largest accessibility text size takes every bar to icon-only`() {
        // 2x needs 1680dp, which no viewport this app runs on has — deliberately.
        showSheetButtonLabels(1000.dp, fontScale = 2f) shouldBe false
    }

    @Test
    fun `smaller text does not lower the threshold`() {
        // The sweep's number is a floor: a row judged too tight for these words stays icon-only.
        showSheetButtonLabels(800.dp, fontScale = 0.85f) shouldBe false
        showSheetButtonLabels(840.dp, fontScale = 0.85f) shouldBe true
    }
}

/**
 * Unit tests for the two pieces of arithmetic behind the seek bar's accessibility (A11Y-P-04/05):
 * where a custom-action seek lands, and how a position is put into words.
 */
class ScrubberSemanticsTest {
    @Test
    fun `skipping forward moves by the transport's own amount`() {
        seekTargetMs(
            positionMs = 1.minutes.inWholeMilliseconds,
            deltaMs = SKIP_FORWARD_MS,
            durationMs = 45.minutes.inWholeMilliseconds,
        ) shouldBe 90.seconds.inWholeMilliseconds
    }

    @Test
    fun `skipping back near the start lands at the start, not before it`() {
        seekTargetMs(
            positionMs = 4.seconds.inWholeMilliseconds,
            deltaMs = -SKIP_BACK_MS,
            durationMs = 45.minutes.inWholeMilliseconds,
        ) shouldBe 0L
    }

    @Test
    fun `skipping forward near the end lands at the end, not past it`() {
        val duration = 45.minutes.inWholeMilliseconds
        seekTargetMs(
            positionMs = duration - 5.seconds.inWholeMilliseconds,
            deltaMs = SKIP_FORWARD_MS,
            durationMs = duration,
        ) shouldBe duration
    }

    @Test
    fun `an unknown duration still clamps at zero and does not invent an end`() {
        // Duration is 0 until the player reports one; a forward skip there must not be clamped to 0.
        seekTargetMs(positionMs = 0L, deltaMs = -SKIP_BACK_MS, durationMs = 0L) shouldBe 0L
        seekTargetMs(positionMs = 0L, deltaMs = SKIP_FORWARD_MS, durationMs = 0L) shouldBe SKIP_FORWARD_MS
    }

    @Test
    fun `a position under an hour is spoken in minutes and seconds`() {
        (12.minutes + 34.seconds).inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.MINUTES, 12L),
                SpokenTimePart(SpokenTimeUnit.SECONDS, 34L),
            )
    }

    @Test
    fun `an exact number of minutes does not say zero seconds`() {
        45.minutes.inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(SpokenTimePart(SpokenTimeUnit.MINUTES, 45L))
    }

    @Test
    fun `past an hour the seconds are dropped rather than read out every time`() {
        (1.hours + 3.minutes + 12.seconds).inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.HOURS, 1L),
                SpokenTimePart(SpokenTimeUnit.MINUTES, 3L),
            )
    }

    @Test
    fun `an exact hour still says its zero minutes, so the unit is not ambiguous`() {
        2.hours.inWholeMilliseconds.asSpokenTimeParts() shouldBe
            listOf(
                SpokenTimePart(SpokenTimeUnit.HOURS, 2L),
                SpokenTimePart(SpokenTimeUnit.MINUTES, 0L),
            )
    }

    @Test
    fun `the very start of a film still says where it is`() {
        0L.asSpokenTimeParts() shouldBe listOf(SpokenTimePart(SpokenTimeUnit.SECONDS, 0L))
    }

    @Test
    fun `a negative position is read as the start rather than as a negative time`() {
        (-5_000L).asSpokenTimeParts() shouldBe listOf(SpokenTimePart(SpokenTimeUnit.SECONDS, 0L))
    }
}
