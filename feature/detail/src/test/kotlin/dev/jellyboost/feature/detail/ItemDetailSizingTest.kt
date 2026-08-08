package dev.jellyboost.feature.detail

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [backdropHeight] and [detailLayoutFor] — the two viewport-driven decisions that
 * make up the item-detail screen's responsive layout.
 *
 * Phone landscape (~800×360dp) used to be misread as the wide/tablet shape: it clears
 * [WIDE_BREAKPOINT] on width alone, so the header laid out side by side and the banner took the
 * fixed tablet height on a viewport far too short for either. [WIDE_MIN_HEIGHT] rules that shape
 * out — the cases below cover every quadrant (phone/tablet × portrait/landscape) to pin the fix
 * without moving tablet behavior at all.
 *
 * The expected heights are the 2026-refresh values (DECISIONS.md 2026-08-01, "card metrics and
 * radii leave the jellyfin-web footprint"): the banner now carries the title lockup, so both
 * portrait fractions and both floors went up. Same viewports, same assertions — only the numbers
 * the refresh moved.
 *
 * The layout assertions are [DetailLayout] rather than the old `isWideLayout` boolean (audit
 * 2026-08-08, UI-8). Every case that used to read `shouldBe false` now says *which* non-wide shape
 * it is, which is the distinction the two-boolean version could not make.
 */
class ItemDetailSizingTest {
    @Test
    fun `phone portrait gets a proportional banner and the stacked header`() {
        // 360x800: portrait, and narrower than COMPACT_MAX_WIDTH (480dp) so the compact fraction
        // applies — 0.52 * 800 = 416dp, which sits inside [320, 560] so the proportional value wins
        // outright.
        backdropHeight(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe 416.dp
        detailLayoutFor(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe DetailLayout.COMPACT
    }

    @Test
    fun `portrait banner fraction switches right at the compact width cutoff`() {
        // 479x900: just below COMPACT_MAX_WIDTH (480dp), so the compact fraction applies —
        // 0.52 * 900, which in the Float arithmetic `Dp.times` performs lands one unit in the last
        // place below 468dp rather than exactly on it. Pinned as computed, not as rounded: the
        // point of the case is the *fraction* that was used, and 468 would fail on a hair.
        backdropHeight(maxWidth = 479.dp, maxHeight = 900.dp) shouldBe 467.99997.dp
        // 480x900: at COMPACT_MAX_WIDTH, so the ordinary (wider) fraction applies —
        // 0.46 * 900 = 414dp.
        backdropHeight(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe 414.dp
    }

    @Test
    fun `phone landscape no longer takes the fixed tablet banner or the wide header`() {
        // 800x330: width alone would clear WIDE_BREAKPOINT, but the viewport is far shorter than
        // WIDE_MIN_HEIGHT, so both the wide header and the fixed 360dp banner are ruled out — the
        // banner instead takes half of the (scarce) height: 0.5 * 330 = 165dp.
        backdropHeight(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe 165.dp
        // Wide but short: MEDIUM, not COMPACT. It is not a phone-width screen, and MEDIUM is what
        // keeps it clamping the overview the way the old `compact` boolean did not.
        detailLayoutFor(maxWidth = 800.dp, maxHeight = 330.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `tablet landscape keeps the fixed banner and the wide header`() {
        // 1138x630: both dimensions clear their thresholds, so the banner is the fixed
        // WIDE_BACKDROP_HEIGHT — not a share of the (still generous) height.
        backdropHeight(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe 360.dp
        detailLayoutFor(maxWidth = 1138.dp, maxHeight = 630.dp) shouldBe DetailLayout.WIDE
    }

    @Test
    fun `tablet portrait keeps its proportional banner and the stacked header`() {
        // 711x1138: portrait, and width sits just below WIDE_BREAKPOINT (720dp) so the floor is
        // still the narrow fixed value (NARROW_BACKDROP_HEIGHT = 320dp) — 0.46 * 1138 = 523.48dp,
        // which is within [320, 560], so the proportional value wins. Not the wide stage, on width
        // alone, same as today — and MEDIUM rather than COMPACT, which is what it always was
        // visually.
        backdropHeight(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe 523.48.dp
        detailLayoutFor(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `the middle band is its own shape rather than a wide-and-compact contradiction`() {
        // The 480-720dp band the two-boolean version had no answer for: `isWide` was false (width
        // below 720) and `compact` was false (width at or above 480), so the screen took the
        // stacked header *and* ran the overview unclamped. Both edges of the band, in portrait and
        // in landscape.
        detailLayoutFor(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 719.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 600.dp, maxHeight = 500.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `the compact width cutoff is exclusive and the wide one inclusive`() {
        // 479 is the last compact width; 480 is the first medium one.
        detailLayoutFor(maxWidth = 479.dp, maxHeight = 900.dp) shouldBe DetailLayout.COMPACT
        detailLayoutFor(maxWidth = 480.dp, maxHeight = 900.dp) shouldBe DetailLayout.MEDIUM
        // 719 is the last medium width; 720 is the first wide one, given the height clears.
        detailLayoutFor(maxWidth = 719.dp, maxHeight = 480.dp) shouldBe DetailLayout.MEDIUM
        detailLayoutFor(maxWidth = 720.dp, maxHeight = 480.dp) shouldBe DetailLayout.WIDE
        // ...and 479dp of height is one short, whatever the width.
        detailLayoutFor(maxWidth = 720.dp, maxHeight = 479.dp) shouldBe DetailLayout.MEDIUM
    }

    @Test
    fun `only the wide stage runs the overview in full`() {
        // The behaviour change UI-8 is about: MEDIUM clamps, where it used not to.
        DetailLayout.COMPACT.clampsOverview shouldBe true
        DetailLayout.MEDIUM.clampsOverview shouldBe true
        DetailLayout.WIDE.clampsOverview shouldBe false
    }
}
