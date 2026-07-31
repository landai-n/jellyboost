package dev.jellyboost.player.ui

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [showSheetButtonLabels] — the one decision behind the bottom bar's labelled and
 * icon-only pickers, kept out of the composable so the threshold can be checked without a device.
 */
class PlayerControlsTest {
    @Test
    fun `a phone in landscape gets icon-only pickers`() {
        // The sweep's worst case: five pickers and the clock had near-zero slack at this width.
        showSheetButtonLabels(800.dp) shouldBe false
    }

    @Test
    fun `a tablet in portrait gets icon-only pickers`() {
        showSheetButtonLabels(711.dp) shouldBe false
    }

    @Test
    fun `the threshold itself is labelled`() {
        showSheetButtonLabels(840.dp) shouldBe true
    }

    @Test
    fun `the capped tablet-landscape bar keeps its labels`() {
        // The bar is capped at 1000dp, so this is what the tablet renders — it must not change.
        showSheetButtonLabels(1000.dp) shouldBe true
    }

    @Test
    fun `a hand-held width well under the threshold is icon-only`() {
        showSheetButtonLabels(640.dp) shouldBe false
    }
}
