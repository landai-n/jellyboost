package dev.jellyboost.feature.detail

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [backdropHeight] and [isWideLayout] — the two viewport-driven decisions that make
 * up the item-detail screen's responsive layout.
 *
 * Phone landscape (~800×360dp) used to be misread as the wide/tablet shape: it clears
 * [WIDE_BREAKPOINT] on width alone, so the header laid out side by side and the banner took the
 * fixed tablet height on a viewport far too short for either. [WIDE_MIN_HEIGHT] rules that shape
 * out — the cases below cover every quadrant (phone/tablet × portrait/landscape) to pin the fix
 * without moving tablet behavior at all.
 */
class ItemDetailSizingTest {
    @Test
    fun `phone portrait gets a proportional banner and the stacked header`() {
        // 360x800: portrait, width below WIDE_BREAKPOINT so the floor is the narrow fixed value —
        // 0.40 * 800 = 320dp, which sits inside [220, 560] so the proportional value wins outright.
        backdropHeight(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe 320.dp
        isWideLayout(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe false
    }

    @Test
    fun `phone landscape no longer takes the fixed tablet banner or the wide header`() {
        // 800x330: width alone would clear WIDE_BREAKPOINT, but the viewport is far shorter than
        // WIDE_MIN_HEIGHT, so both the wide header and the fixed 320dp banner are ruled out — the
        // banner instead takes half of the (scarce) height: 0.5 * 330 = 165dp.
        backdropHeight(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe 165.dp
        isWideLayout(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe false
    }

    @Test
    fun `tablet landscape keeps the fixed banner and the wide header, unchanged`() {
        // 1138x630: both dimensions clear their thresholds, so this must keep exactly today's
        // tablet behavior — the fixed WIDE_BACKDROP_HEIGHT, not a share of the (still generous)
        // height.
        backdropHeight(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe 320.dp
        isWideLayout(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe true
    }

    @Test
    fun `tablet portrait keeps its proportional banner and the stacked header`() {
        // 711x1138: portrait, and width sits just below WIDE_BREAKPOINT (720dp) so the floor is
        // still the narrow fixed value (Dimens.BackdropHeight = 220dp) — 0.40 * 1138 = 455.2dp,
        // which is within [220, 560], so the proportional value wins. isWide is false on width
        // alone, same as today.
        backdropHeight(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe 455.2.dp
        isWideLayout(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe false
    }
}
