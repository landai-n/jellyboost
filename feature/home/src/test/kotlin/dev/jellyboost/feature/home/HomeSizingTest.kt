package dev.jellyboost.feature.home

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Slack allowed when a dp value is the product of a float fraction rather than a literal. */
private const val TOLERANCE = 0.01f

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
        heroHeight(wide = false, viewportHeight = 640.dp).value shouldBe (384f plusOrMinus TOLERANCE)
        heroHeight(wide = true, viewportHeight = 600.dp).value shouldBe (360f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a tablet in landscape gets the mocks' 400dp hero`() {
        heroHeight(wide = true, viewportHeight = 711.dp) shouldBe 400.dp
    }

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

    @Test
    fun `the copy zone is the lockup's own height, which is what the scrim's plateau is anchored to`() {
        // The mocks' banner at scale 1.0: two 20dp paddings, three 12dp gaps, 155dp of text.
        compactHeroCopyZone(heroHeight = 460.dp).value shouldBe (231f plusOrMinus TOLERANCE)
        // And at 2.0, where the lockup has grown by its whole text height and climbed the picture.
        compactHeroCopyZone(heroHeight = 615.dp, fontScale = 2f).value shouldBe (386f plusOrMinus TOLERANCE)
    }

    @Test
    fun `the copy zone sheds with the lockup rather than claiming room the copy gave up`() {
        val banner = heroHeight(wide = false, viewportHeight = 360.dp)

        compactHeroShowsSecondary(banner) shouldBe false
        compactHeroCopyZone(banner).value shouldBe (176f plusOrMinus TOLERANCE)
    }

    @Test
    fun `the copy zone never claims more of the banner than the lockup can occupy`() {
        listOf(1f, 1.3f, 1.6f, 2f).forEach { scale ->
            listOf(360.dp, 460.dp, 615.dp, 800.dp).forEach { banner ->
                val zone = compactHeroCopyZone(heroHeight = banner, fontScale = scale)
                (zone.value > 0f) shouldBe true
            }
        }
    }

    @Test
    fun `the wash holds its plateau past the copy column, not past a fraction of the window`() {
        // 24dp of padding plus the 420dp cap the column reaches on every wide window.
        WideCopyEdge.value shouldBe (444f plusOrMinus TOLERANCE)
    }

    @Test
    fun `a phone-landscape banner drops the compact lockup's secondary lines`() {
        val banner = heroHeight(wide = false, viewportHeight = 360.dp)

        compactHeroShowsSecondary(banner) shouldBe false
    }

    @Test
    fun `portrait phone banners keep the full compact lockup`() {
        compactHeroShowsSecondary(heroHeight(wide = false, viewportHeight = 640.dp)) shouldBe true
        compactHeroShowsSecondary(heroHeight(wide = false, viewportHeight = 800.dp)) shouldBe true
    }

    @Test
    fun `nothing about the default font scale changed`() {
        listOf(360.dp, 640.dp, 800.dp, 1138.dp).forEach { viewport ->
            heroHeight(wide = false, viewportHeight = viewport, fontScale = 1f) shouldBe
                heroHeight(wide = false, viewportHeight = viewport)
            heroHeight(wide = true, viewportHeight = viewport, fontScale = 1f) shouldBe
                heroHeight(wide = true, viewportHeight = viewport)
        }
        compactHeroShowsSecondary(260.dp, fontScale = 1f) shouldBe true
        compactHeroShowsSecondary(259.dp, fontScale = 1f) shouldBe false
    }

    @Test
    fun `a font scale below one never shrinks the banner`() {
        heroHeight(wide = false, viewportHeight = 800.dp, fontScale = 0.85f) shouldBe 460.dp
        compactHeroShowsSecondary(260.dp, fontScale = 0.85f) shouldBe true
    }

    @Test
    fun `a roomy phone grows its banner with the text in it`() {
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
        val banner = heroHeight(wide = false, viewportHeight = 360.dp, fontScale = 2f)

        banner.value shouldBe (270f plusOrMinus TOLERANCE)
        compactHeroShowsSecondary(banner, fontScale = 2f) shouldBe false
        compactHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 1
        compactHeroTitleMaxLines(heroHeight(wide = false, viewportHeight = 360.dp)) shouldBe 2
    }

    @Test
    fun `the wide hero keeps everything at the default scale, at every banner height it is drawn at`() {
        listOf(360.dp, 400.dp, 460.dp).forEach { banner ->
            wideHeroShowsSecondary(banner) shouldBe true
            wideHeroTitleMaxLines(banner) shouldBe 2
        }
    }

    @Test
    fun `the shortest wide banner there is sheds its secondary lines instead of clipping a button`() {
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
        val banner = heroHeight(wide = true, viewportHeight = 711.dp, fontScale = 2f)

        wideHeroShowsSecondary(banner, fontScale = 2f) shouldBe false
        wideHeroTitleMaxLines(banner, fontScale = 2f) shouldBe 2
        (wideHeroCopyHeight(banner).value >= WIDE_CONDENSED_LOCKUP_AT_2X) shouldBe true
    }

    @Test
    fun `the copy band still stops short of the rail at every font scale`() {
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

/** A two-line 44sp title, the buttons and one gap, at font scale 2.0. */
private const val WIDE_CONDENSED_LOCKUP_AT_2X = 300f
