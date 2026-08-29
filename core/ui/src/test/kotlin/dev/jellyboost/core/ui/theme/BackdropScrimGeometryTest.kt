package dev.jellyboost.core.ui.theme

import org.junit.jupiter.api.Test

/**
 * The doctrine's load-bearing claim is geometric, not chromatic: `ContrastRatioTest` measures the
 * inks against the scrim's plateau, and those numbers are only true of the app if the plateau is
 * actually being held everywhere a lockup is drawn. That is what this pins.
 *
 * The ramps used to place their strength at a *fraction* of the banner while the lockup is measured
 * in dp, which put full protection at the foot and the copy 100–220dp above it.
 */
class BackdropScrimGeometryTest {
    @Test
    fun `the plateau is already held at the copy zone's ceiling`() {
        val stops = lightStops(heightPx = 460f, copyZonePx = 231f)
        alphaAt(stops, 1f - 231f / 460f) shouldBe PLATEAU
    }

    @Test
    fun `every altitude below the ceiling keeps at least the plateau`() {
        val stops = lightStops(heightPx = 460f, copyZonePx = 231f)
        val ceiling = 1f - 231f / 460f
        generateSequence(ceiling) { it + STEP }
            .takeWhile { it <= 1f }
            .forEach { check(alphaAt(stops, it) >= PLATEAU - TOLERANCE) { "thin at $it" } }
    }

    @Test
    fun `a copy zone that grows with the font scale takes the plateau up with it`() {
        // The compact hero at scale 2: a 386dp lockup in a 615dp banner.
        val stops = lightStops(heightPx = 615f, copyZonePx = 386f)
        alphaAt(stops, 1f - 386f / 615f) shouldBe PLATEAU
        // And the top of the picture is still left alone.
        alphaAt(stops, 0f) shouldBe 0f
    }

    @Test
    fun `a banner with no room for the climb starts part-way up it rather than jumping`() {
        val stops = lightStops(heightPx = 300f, copyZonePx = 280f)
        val start = alphaAt(stops, 0f)
        check(start > 0f && start < PLATEAU) { "expected a partial start, was $start" }
        alphaAt(stops, 1f) shouldBe PLATEAU
    }

    @Test
    fun `the light ramp holds the plateau flat to the foot`() {
        alphaAt(lightStops(heightPx = 460f, copyZonePx = 231f), 1f) shouldBe PLATEAU
    }

    @Test
    fun `the dark ramp keeps a page dissolve under the plateau`() {
        val stops =
            backdropScrimStops(
                heightPx = 460f,
                copyZonePx = 231f,
                risePx = RISE,
                footRunPx = FOOT_RUN,
                footAlpha = 1f,
            )
        alphaAt(stops, 1f) shouldBe 1f
        alphaAt(stops, 1f - 231f / 460f) shouldBe PLATEAU
    }

    @Test
    fun `the dark ramp still dissolves where there is no copy at all`() {
        val stops =
            backdropScrimStops(
                heightPx = 400f,
                copyZonePx = 0f,
                risePx = RISE,
                footRunPx = FOOT_RUN,
                footAlpha = 1f,
            )
        alphaAt(stops, 1f) shouldBe 1f
        alphaAt(stops, 1f - FOOT_RUN / 400f) shouldBe PLATEAU
    }

    @Test
    fun `stops are strictly increasing and end at the foot`() {
        listOf(0f, 120f, 231f, 386f, 600f).forEach { copyZone ->
            val stops = lightStops(heightPx = 460f, copyZonePx = copyZone)
            check(stops.last().first == 1f) { "no foot stop for $copyZone" }
            stops.zipWithNext { a, b -> check(b.first > a.first) { "not increasing at $copyZone: $stops" } }
        }
    }

    @Test
    fun `the wash holds the plateau past the copy column's far edge`() {
        // The narrowest wide window: 600dp, where the fraction ramp used to have let go already.
        val stops = wideHeroWashStops(widthPx = 600f, holdPx = 468f, fadePx = 280f)
        alphaAt(stops, 468f / 600f) shouldBe JellyfinGradients.BACKDROP_PLATEAU_ALPHA
        check(alphaAt(stops, 0f) == JellyfinGradients.WIDE_HERO_NEAR_ALPHA)
    }

    @Test
    fun `the wash reaches transparent when the window is wide enough for its fade`() {
        val stops = wideHeroWashStops(widthPx = 1200f, holdPx = 468f, fadePx = 280f)
        alphaAt(stops, 468f / 1200f) shouldBe JellyfinGradients.BACKDROP_PLATEAU_ALPHA
        alphaAt(stops, 748f / 1200f) shouldBe 0f
        alphaAt(stops, 1f) shouldBe 0f
    }

    @Test
    fun `a window narrower than the copy column keeps the wash across all of it`() {
        val stops = wideHeroWashStops(widthPx = 400f, holdPx = 468f, fadePx = 280f)
        check(alphaAt(stops, 1f) >= JellyfinGradients.BACKDROP_PLATEAU_ALPHA)
    }

    private fun lightStops(
        heightPx: Float,
        copyZonePx: Float,
    ) = backdropScrimStops(
        heightPx = heightPx,
        copyZonePx = copyZonePx,
        risePx = RISE,
        footRunPx = 0f,
        footAlpha = PLATEAU,
    )

    /** What the gradient shader does between two stops, so the assertions read the drawn ramp. */
    private fun alphaAt(
        stops: List<Pair<Float, Float>>,
        at: Float,
    ): Float {
        val after = stops.indexOfFirst { it.first >= at }
        if (after <= 0) return stops.first().second
        val (x0, a0) = stops[after - 1]
        val (x1, a1) = stops[after]
        return a0 + (a1 - a0) * ((at - x0) / (x1 - x0))
    }

    private infix fun Float.shouldBe(expected: Float) =
        check(kotlin.math.abs(this - expected) <= TOLERANCE) { "expected $expected, was $this" }

    private companion object {
        const val PLATEAU = JellyfinGradients.BACKDROP_PLATEAU_ALPHA
        const val RISE = 140f
        const val FOOT_RUN = 140f
        const val STEP = 0.01f
        const val TOLERANCE = 0.001f
    }
}
