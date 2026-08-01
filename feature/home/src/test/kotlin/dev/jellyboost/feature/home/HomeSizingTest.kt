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
}
