package dev.jellyboost.player.cast

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The positions here are the device-measured ones (a 24 fps film, 3.000 s segments): starts at
 * 3056.881 s and 3203.590 s (2.881 s and 2.590 s into segments 1018 and 1067) stalled the receiver
 * forever; 0.648 s, 0.711 s, 1.307 s, 1.59 s and 1.88 s into a segment played. The rule keeps a
 * margin below all of them: anything more than 1 s in is moved, so the measured-good 1.307 s–1.88 s
 * starts move too — by under 0.9 s.
 */
class HlsSegmentSnapTest {
    // ---- segment length ------------------------------------------------------------------------

    @Test
    fun `a 24 fps transcode is cut every 72 frames, exactly 3 s`() {
        HlsSegmentSnap.segmentMs(24f) shouldBe 3_000.0
    }

    @Test
    fun `a 23_976 fps transcode is cut every 72 frames too, 3_003 s`() {
        // The server's own restarts for such a film: -ss 00:03:09.189 (63 x 3.003) and
        // 00:22:07.326 (442 x 3.003), both with -g 72.
        val segment = requireNotNull(HlsSegmentSnap.segmentMs(23.976025f))

        segment shouldBe (3_003.0 plusOrMinus 0.01)
        (63 * segment) shouldBe (189_189.0 plusOrMinus 1.0)
        (442 * segment) shouldBe (1_327_326.0 plusOrMinus 1.0)
    }

    @Test
    fun `other common rates round up to whole frames`() {
        HlsSegmentSnap.segmentMs(25f) shouldBe 3_000.0
        requireNotNull(HlsSegmentSnap.segmentMs(29.97003f)) shouldBe (3_003.0 plusOrMinus 0.01)
        requireNotNull(HlsSegmentSnap.segmentMs(59.94006f)) shouldBe (3_003.0 plusOrMinus 0.01)
        HlsSegmentSnap.segmentMs(24f, nominalSeconds = 6) shouldBe 6_000.0
    }

    @Test
    fun `an unknown or nonsensical frame rate has no segment length`() {
        HlsSegmentSnap.segmentMs(null) shouldBe null
        HlsSegmentSnap.segmentMs(0f) shouldBe null
        HlsSegmentSnap.segmentMs(-24f) shouldBe null
        HlsSegmentSnap.segmentMs(Float.NaN) shouldBe null
        HlsSegmentSnap.segmentMs(Float.POSITIVE_INFINITY) shouldBe null
        HlsSegmentSnap.segmentMs(24f, nominalSeconds = 0) shouldBe null
    }

    // ---- snapping, 24 fps ----------------------------------------------------------------------

    private val at24 = HlsSegmentSnap.segmentMs(24f)

    @Test
    fun `the two starts that stalled are moved to 1 s into their segment`() {
        HlsSegmentSnap.snapStartMs(3_056_881L, at24) shouldBe 3_055_000L
        HlsSegmentSnap.snapStartMs(3_203_590L, at24) shouldBe 3_202_000L
    }

    @Test
    fun `starts early in a segment are left alone`() {
        HlsSegmentSnap.snapStartMs(3_054_648L, at24) shouldBe 3_054_648L
        HlsSegmentSnap.snapStartMs(648L, at24) shouldBe 648L
        HlsSegmentSnap.snapStartMs(3_000_711L, at24) shouldBe 3_000_711L
    }

    @Test
    fun `exact boundaries`() {
        // A segment's first frame, and exactly 1 s in: unchanged.
        HlsSegmentSnap.snapStartMs(3_054_000L, at24) shouldBe 3_054_000L
        HlsSegmentSnap.snapStartMs(3_055_000L, at24) shouldBe 3_055_000L
        // One millisecond further, and the last millisecond of the segment: to 1 s in.
        HlsSegmentSnap.snapStartMs(3_055_001L, at24) shouldBe 3_055_000L
        HlsSegmentSnap.snapStartMs(3_056_999L, at24) shouldBe 3_055_000L
        // The next segment's first frame belongs to the next segment.
        HlsSegmentSnap.snapStartMs(3_057_000L, at24) shouldBe 3_057_000L
    }

    @Test
    fun `a snapped start is never later, never more than 2 s earlier, and never zero`() {
        for (position in listOf(1_001L, 2_999L, 1_500_000L, 3_056_881L, 7_199_999L)) {
            val start = HlsSegmentSnap.snapStartMs(position, at24)
            (start <= position) shouldBe true
            (position - start < 2_000L) shouldBe true
            (start > 0L) shouldBe true
        }
    }

    @Test
    fun `zero and negative starts are left as they are`() {
        HlsSegmentSnap.snapStartMs(0L, at24) shouldBe 0L
        HlsSegmentSnap.snapStartMs(-5L, at24) shouldBe -5L
    }

    // ---- snapping, 23.976 fps ------------------------------------------------------------------

    private val at23976 = HlsSegmentSnap.segmentMs(23.976025f)

    @Test
    fun `late in a 3_003 s segment is moved to 1 s into it`() {
        // Segment 442 starts at 1327.326 s.
        HlsSegmentSnap.snapStartMs(1_327_326L + 2_881L, at23976) shouldBe 1_328_326L - 1L
        // Segment 1018 starts at 3057.054 s, not 3054.000 s as a 3.000 s grid would have it.
        HlsSegmentSnap.snapStartMs(3_057_054L + 2_500L, at23976).shouldBeWithinAMillisecondOf(3_058_054L)
    }

    @Test
    fun `early in a 3_003 s segment is left alone, and the 3_003 s grid is the one used`() {
        HlsSegmentSnap.snapStartMs(1_327_326L + 700L, at23976) shouldBe 1_328_026L
        HlsSegmentSnap.snapStartMs(1_327_326L, at23976) shouldBe 1_327_326L
        // The real grid decides: 3056.900 s is 2.849 s into segment 1017 (from 3054.051 s), where a
        // 3.000 s grid would have called it 2.900 s into segment 1018 and sent it to 3055.000 s.
        HlsSegmentSnap.snapStartMs(3_056_900L, at23976).shouldBeWithinAMillisecondOf(3_055_051L)
    }

    // ---- no grid -------------------------------------------------------------------------------

    @Test
    fun `no segment length (direct play, unknown frame rate) means no snap`() {
        HlsSegmentSnap.snapStartMs(3_056_881L, null) shouldBe 3_056_881L
        HlsSegmentSnap.snapStartMs(3_056_881L, HlsSegmentSnap.segmentMs(null)) shouldBe 3_056_881L
    }

    /** The float frame rate leaves the 3.003 s grid a fraction of a millisecond off the ideal one. */
    private fun Long.shouldBeWithinAMillisecondOf(expected: Long) {
        (kotlin.math.abs(this - expected) <= 1L) shouldBe true
    }
}
