package dev.jellyboost.feature.home

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Slack allowed when a dp value is the product of a float fraction rather than a literal. */
private const val TOLERANCE = 0.01f

/**
 * Unit tests for the home screen's measured-width decisions: [homeThumbCardWidth] (how wide a
 * thumb-shaped card is), [isWideHome] (which shape the screen draws), [heroHeight] (how tall the
 * *Continue watching* banner is) and the band that banner's copy is laid out in
 * ([wideHeroCopyTopInset], [wideHeroCopyHeight]).
 *
 * A phone-width viewport (360dp, 328dp available after [Dimens.ScreenPadding]) used to fit only
 * ~1.4 of the tablet-calibrated [Dimens.ThumbWidth] cards per row, reading as zoomed-in.
 * These tests pin the compact branch (160dp, below 600dp — sized so two full cards plus a peek of
 * a third fit) and the tablet branch (unchanged at [Dimens.ThumbWidth], at and above 600dp) so
 * that regression can't sneak back in.
 */
class HomeSizingTest {
    @Test
    fun `a 360dp phone viewport uses the compact width`() {
        homeThumbCardWidth(360.dp) shouldBe 160.dp
    }

    @Test
    fun `just under the compact cutoff still uses the compact width`() {
        homeThumbCardWidth(599.dp) shouldBe 160.dp
    }

    @Test
    fun `the compact cutoff itself uses the tablet width`() {
        homeThumbCardWidth(600.dp) shouldBe Dimens.ThumbWidth
    }

    @Test
    fun `the test tablet portrait width uses the tablet width, unchanged`() {
        homeThumbCardWidth(711.dp) shouldBe Dimens.ThumbWidth
    }

    @Test
    fun `a phone in portrait is not the wide shape`() {
        isWideHome(maxWidth = 360.dp, maxHeight = 800.dp) shouldBe false
    }

    @Test
    fun `a phone in landscape is wide enough but far too short for the wide shape`() {
        isWideHome(maxWidth = 800.dp, maxHeight = 360.dp) shouldBe false
    }

    @Test
    fun `both test tablet orientations draw the wide shape`() {
        isWideHome(maxWidth = 711.dp, maxHeight = 1138.dp) shouldBe true
        isWideHome(maxWidth = 1138.dp, maxHeight = 711.dp) shouldBe true
    }

    @Test
    fun `a roomy phone gets the mocks' 460dp hero`() {
        heroHeight(wide = false, viewportHeight = 800.dp) shouldBe 460.dp
    }

    @Test
    fun `a short viewport caps the hero at three fifths of its height`() {
        // Compared with a tolerance rather than `shouldBe 384.dp`: the cap is a float multiplication,
        // and 640 * 0.6f is a hair off the decimal value on either side of the comparison.
        heroHeight(wide = false, viewportHeight = 640.dp).value shouldBe (384f plusOrMinus TOLERANCE)
        heroHeight(wide = true, viewportHeight = 600.dp).value shouldBe (360f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a tablet in landscape gets the mocks' 400dp hero`() {
        heroHeight(wide = true, viewportHeight = 711.dp) shouldBe 400.dp
    }

    // ---- the wide hero's copy band ----------------------------------------------------------

    @Test
    fun `the mocks' banner keeps the calibrated copy inset exactly`() {
        wideHeroCopyTopInset(400.dp).value shouldBe (104f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a taller banner never pushes the copy further down than the nav needs`() {
        wideHeroCopyTopInset(600.dp) shouldBe 104.dp
    }

    @Test
    fun `a capped short banner gives the copy back the room a flat inset would have wasted`() {
        // A 600dp-tall window caps the hero at 360dp; the flat 104dp inset would have left the
        // lockup 208dp, which a two-line title plus the buttons does not fit inside.
        val short = heroHeight(wide = true, viewportHeight = 600.dp)

        wideHeroCopyTopInset(short).value shouldBe (93.6f plusOrMinus TOLERANCE)
        wideHeroCopyHeight(short).value shouldBe (218.4f plusOrMinus TOLERANCE)
    }

    @Test
    fun `the copy band of the mocks' banner is the banner minus the nav inset and the rail`() {
        wideHeroCopyHeight(400.dp).value shouldBe (248f plusOrMinus TOLERANCE)
    }

    @Test
    fun `the copy always stops short of the rail the rows below overlap into`() {
        // The invariant behind the bug: whatever the banner's height, the copy — the resume button
        // included — is laid out inside a band that ends before the next section rises into it.
        listOf(336.dp, 360.dp, 400.dp, 460.dp).forEach { banner ->
            val band = wideHeroCopyHeight(banner)
            (band + wideHeroCopyTopInset(banner) + HeroRailOverlap).value shouldBe
                (banner.value plusOrMinus TOLERANCE)
            (band.value <= (banner - HeroRailOverlap).value) shouldBe true
        }
    }

    @Test
    fun `an absurdly short banner leaves an empty copy band rather than a negative one`() {
        wideHeroCopyHeight(40.dp) shouldBe 0.dp
    }

    // ---- the compact hero's condensed lockup ------------------------------------------------

    @Test
    fun `a phone-landscape banner drops the compact lockup's secondary lines`() {
        // A 360dp-tall landscape phone is not the wide shape (too short), so it draws the compact
        // banner — capped at 216dp, which cannot hold the eyebrow and the metadata line on top of
        // the title and the buttons.
        val banner = heroHeight(wide = false, viewportHeight = 360.dp)

        compactHeroShowsSecondary(banner) shouldBe false
    }

    @Test
    fun `portrait phone banners keep the full compact lockup`() {
        // The capped 640dp phone (384dp of banner) and the roomy one (the mocks' 460dp) both fit
        // the whole lockup.
        compactHeroShowsSecondary(heroHeight(wide = false, viewportHeight = 640.dp)) shouldBe true
        compactHeroShowsSecondary(heroHeight(wide = false, viewportHeight = 800.dp)) shouldBe true
    }

    // ---- accessibility font scales (audit A11Y-16) -------------------------------------------
    //
    // Every threshold above was calibrated at font scale 1.0 and compared dp against text; these
    // pin that the default scale is unchanged to the pixel *and* that a banner asked to hold twice
    // as much text either grows or sheds, rather than clipping the copy and the buttons.

    @Test
    fun `nothing about the default font scale changed`() {
        listOf(360.dp, 640.dp, 800.dp, 1138.dp).forEach { viewport ->
            heroHeight(wide = false, viewportHeight = viewport, fontScale = 1f) shouldBe
                heroHeight(wide = false, viewportHeight = viewport)
            heroHeight(wide = true, viewportHeight = viewport, fontScale = 1f) shouldBe
                heroHeight(wide = true, viewportHeight = viewport)
        }
        // The one boundary the compact shed was calibrated on, held exactly.
        compactHeroShowsSecondary(260.dp, fontScale = 1f) shouldBe true
        compactHeroShowsSecondary(259.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `a font scale below one never shrinks the banner`() {
        // Android does not offer one today, and a hero smaller than the mocks' is nobody's fix.
        heroHeight(wide = false, viewportHeight = 800.dp, fontScale = 0.85f) shouldBe 460.dp
        compactHeroShowsSecondary(260.dp, fontScale = 0.85f) shouldBe true
    }

    @Test
    fun `a roomy phone grows its banner with the text in it`() {
        // 460dp of banner plus the 155dp its lockup's text gains at 2.0x, under the relaxed
        // ceiling (0.75 x 800 = 600dp).
        heroHeight(wide = false, viewportHeight = 800.dp, fontScale = 2f).value shouldBe
            (600f plusOrMinus TOLERANCE)
        heroHeight(wide = false, viewportHeight = 800.dp, fontScale = 1.5f).value shouldBe
            (537.5f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a grown banner still holds the whole compact lockup at 2x`() {
        val banner = heroHeight(wide = false, viewportHeight = 800.dp, fontScale = 2f)

        compactHeroShowsSecondary(banner, fontScale = 2f) shouldBe true
        compactHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 2
    }

    @Test
    fun `a phone in landscape at 2x sheds the secondary lines and the title's second line`() {
        // 360dp of viewport: even the relaxed ceiling only affords a 270dp banner, and the
        // condensed lockup with a two-line 34sp title wants 300dp at 2.0x.
        val banner = heroHeight(wide = false, viewportHeight = 360.dp, fontScale = 2f)

        banner.value shouldBe (270f plusOrMinus TOLERANCE)
        compactHeroShowsSecondary(banner, fontScale = 2f) shouldBe false
        compactHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 1
        // …and at the default scale that same window keeps both lines of the title.
        compactHeroTitleMaxLines(heroHeight(wide = false, viewportHeight = 360.dp)) shouldBe 2
    }

    @Test
    fun `the wide hero keeps everything at the default scale, at every banner height it is drawn at`() {
        // The mocks' 400dp banner and everything the height cap produces on a window tall enough
        // to be the wide shape at all (>= 560dp of viewport, so >= 360dp of banner).
        listOf(360.dp, 400.dp, 460.dp).forEach { banner ->
            wideHeroShowsSecondary(banner) shouldBe true
            wideHeroTitleMaxLines(banner) shouldBe 2
        }
    }

    @Test
    fun `the shortest wide banner there is sheds its secondary lines instead of clipping a button`() {
        // A 560dp-tall window is the shortest `isWideHome` accepts, and its 336dp banner leaves a
        // 200dp copy band — 10dp short of the 211dp the full lockup wants at font scale 1.0, which
        // until now was 10dp taken out of the bottom of the resume button by `clipToBounds`. This
        // is the one place the wide shape's new shedding bites at the default font scale.
        val banner = heroHeight(wide = true, viewportHeight = 560.dp)

        banner.value shouldBe (336f plusOrMinus TOLERANCE)
        wideHeroShowsSecondary(banner) shouldBe false
        wideHeroTitleMaxLines(banner) shouldBe 2
    }

    @Test
    fun `a tablet in portrait grows the wide banner enough to keep the whole lockup at 2x`() {
        val banner = heroHeight(wide = true, viewportHeight = 1138.dp, fontScale = 2f)

        banner.value shouldBe (575f plusOrMinus TOLERANCE)
        wideHeroShowsSecondary(banner, fontScale = 2f) shouldBe true
        wideHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 2
    }

    @Test
    fun `a tablet in landscape at 2x sheds the wide lockup's secondary lines rather than its buttons`() {
        // 711dp of viewport caps the banner at 533dp, whose copy band is 381dp — short of the
        // 386dp the full lockup wants at 2.0x, and comfortably over the 300dp the condensed one
        // needs. Shedding is what keeps the resume button inside the banner (DECISIONS.md
        // 2026-08-01, "the wide hero's copy is height-bounded").
        val banner = heroHeight(wide = true, viewportHeight = 711.dp, fontScale = 2f)

        wideHeroShowsSecondary(banner, fontScale = 2f) shouldBe false
        wideHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 2
        (wideHeroCopyHeight(banner).value >= WIDE_CONDENSED_LOCKUP_AT_2X) shouldBe true
    }

    @Test
    fun `the copy band still stops short of the rail at every font scale`() {
        // The invariant of the 2026-08-01 entry, re-checked on the axis that entry did not have:
        // whatever the scale, the band the copy is laid out in ends before the rows below rise
        // into the banner.
        listOf(1f, 1.3f, 1.5f, 2f).forEach { scale ->
            listOf(360.dp, 640.dp, 800.dp, 1138.dp).forEach { viewport ->
                val banner = heroHeight(wide = true, viewportHeight = viewport, fontScale = scale)
                val band = wideHeroCopyHeight(banner)

                (band + wideHeroCopyTopInset(banner) + HeroRailOverlap).value shouldBe
                    (banner.value plusOrMinus TOLERANCE)
            }
        }
    }
}

/** The wide condensed lockup — a two-line 44sp title, the buttons and one gap — at font scale 2.0. */
private const val WIDE_CONDENSED_LOCKUP_AT_2X = 300f
