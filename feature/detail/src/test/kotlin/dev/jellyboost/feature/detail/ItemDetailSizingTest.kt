package dev.jellyboost.feature.detail

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Phone landscape (~800×360dp) must not read as the wide shape: it clears [WIDE_BREAKPOINT] on width
 * alone. The cases cover every quadrant of phone/tablet × portrait/landscape.
 */
class ItemDetailSizingTest {
    @Test
    fun `phone portrait gets a proportional banner and the stacked header`() {
        // 360x800: under COMPACT_MAX_WIDTH, so 0.52 * 800 = 416dp, inside [320, 560].
        backdropHeight(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe 416.dp
        detailLayoutFor(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe DetailLayout.COMPACT
    }

    @Test
    fun `portrait banner fraction switches right at the compact width cutoff`() {
        // 479x900: 0.52 * 900 lands one ULP below 468dp in `Dp.times`' Float arithmetic. Pinned as
        // computed, not rounded — the case is about *which fraction* was used.
        backdropHeight(maxWidth = 479.dp, maxHeight = 900.dp) shouldBe 467.99997.dp
        // 480x900: at COMPACT_MAX_WIDTH, so 0.46 * 900 = 414dp.
        backdropHeight(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe 414.dp
    }

    @Test
    fun `phone landscape no longer takes the fixed tablet banner or the wide header`() {
        // 800x330: clears WIDE_BREAKPOINT but not WIDE_MIN_HEIGHT, so 0.5 * 330 = 165dp.
        backdropHeight(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe 165.dp
        // Wide but short is MEDIUM, not COMPACT: that is what keeps the overview clamped.
        detailLayoutFor(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `tablet landscape keeps the fixed banner and the wide header`() {
        // 1138x630: both thresholds clear, so the fixed WIDE_BACKDROP_HEIGHT, not a share.
        backdropHeight(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe 360.dp
        detailLayoutFor(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe DetailLayout.WIDE
    }

    @Test
    fun `tablet portrait keeps its proportional banner and the stacked header`() {
        // 711x1138: under WIDE_BREAKPOINT, so the floor is NARROW_BACKDROP_HEIGHT (320dp) and
        // 0.46 * 1138 = 523.48dp wins inside [320, 560].
        backdropHeight(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe 523.48.dp
        detailLayoutFor(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `the middle band is its own shape rather than a wide-and-compact contradiction`() {
        // The 480–720dp band, both edges, portrait and landscape.
        detailLayoutFor(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 719.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 600.dp, maxHeight = 500.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `the compact width cutoff is exclusive and the wide one inclusive`() {
        detailLayoutFor(maxWidth = 479.dp, maxHeight = 900.dp) shouldBe DetailLayout.COMPACT
        detailLayoutFor(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 719.dp, maxHeight = 480.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 720.dp, maxHeight = 480.dp) shouldBe DetailLayout.WIDE
        // 479dp of height is one short, whatever the width.
        detailLayoutFor(maxWidth = 720.dp, maxHeight = 479.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `only the wide stage runs the overview in full`() {
        DetailLayout.COMPACT.clampsOverview shouldBe true
        DetailLayout.MEDIUM.clampsOverview shouldBe true
        DetailLayout.WIDE.clampsOverview shouldBe false
    }
}
