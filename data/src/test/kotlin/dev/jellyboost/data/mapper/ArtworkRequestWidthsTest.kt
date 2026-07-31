package dev.jellyboost.data.mapper

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArtworkRequestWidthsTest {
    @Test
    fun `rounds up to the next bucket`() {
        // 128dp at 2.25x (the test tablet test tablet) is 288px, which lands in the 320 bucket.
        ArtworkRequestWidths.requestWidth(widthDp = 128, density = 2.25f) shouldBe 320
    }

    @Test
    fun `an exact bucket hit is not rounded up to the next one`() {
        ArtworkRequestWidths.requestWidth(widthDp = 160, density = 1f) shouldBe 160
    }

    @Test
    fun `a fractional pixel width still clears the bucket it lands in`() {
        // 143dp at 2.24x is 320.32px: rounding down would ask for fewer pixels than are drawn.
        ArtworkRequestWidths.requestWidth(widthDp = 143, density = 2.24f) shouldBe 400
    }

    @Test
    fun `never requests more than the largest bucket`() {
        ArtworkRequestWidths.requestWidth(widthDp = 4000, density = 4f) shouldBe 1920
    }

    @Test
    fun `never requests fewer pixels than the surface draws`() {
        val densities = listOf(1f, 1.5f, 2f, 2.25f, 2.625f, 3f, 3.5f, 4f)
        val dps = listOf(ArtworkRequestWidths.POSTER_DP, ArtworkRequestWidths.THUMB_DP)
        for (density in densities) {
            for (dp in dps) {
                val requested = ArtworkRequestWidths.requestWidth(dp, density)
                val drawn = kotlin.math.ceil(dp * density.toDouble()).toInt()
                withClue("${dp}dp at ${density}x") { requested shouldBeGreaterThanOrEqual drawn }
            }
        }
    }

    @Test
    fun `forDensity sizes every surface from its own dp knob`() {
        val widths = ArtworkRequestWidths.forDensity(density = 2.25f)

        widths shouldBe
            ArtworkRequestWidths(
                poster = ArtworkRequestWidths.requestWidth(ArtworkRequestWidths.POSTER_DP, 2.25f),
                thumb = ArtworkRequestWidths.requestWidth(ArtworkRequestWidths.THUMB_DP, 2.25f),
                backdrop = ArtworkRequestWidths.requestWidth(ArtworkRequestWidths.BACKDROP_DP, 2.25f),
            )
    }

    @Test
    fun `a denser display asks for a wider image`() {
        val phone = ArtworkRequestWidths.forDensity(density = 3f)
        val tablet = ArtworkRequestWidths.forDensity(density = 2.25f)

        phone.poster shouldBeGreaterThan tablet.poster
    }

    @Test
    fun `rejects a non-positive density`() {
        assertThrows<IllegalArgumentException> {
            ArtworkRequestWidths.requestWidth(widthDp = 128, density = 0f)
        }
    }

    @Test
    fun `rejects a non-positive width`() {
        assertThrows<IllegalArgumentException> {
            ArtworkRequestWidths.requestWidth(widthDp = 0, density = 2f)
        }
    }
}
