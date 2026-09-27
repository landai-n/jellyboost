package dev.jellyboost.player.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The zero rule every report, the scrubber and the last-valid-reading bookkeeping go through. */
class PlaybackSnapshotTest {
    private val later = PlaybackSnapshot(positionMs = 663_000L)
    private val zero = PlaybackSnapshot(positionMs = 0L)

    @Test
    fun `a valid zero after a later valid reading contradicts it`() {
        zero.contradicts(later) shouldBe true
    }

    @Test
    fun `a zero with nothing vouched before it, or after a vouched zero, does not`() {
        zero.contradicts(null) shouldBe false
        // A seek to the start moves the vouched reading to zero first.
        zero.contradicts(later.copy(positionMs = 0L)) shouldBe false
    }

    @Test
    fun `a nonzero reading never contradicts, even one behind the last`() {
        PlaybackSnapshot(positionMs = 1_000L).contradicts(later) shouldBe false
    }

    @Test
    fun `an ended or already invalid reading is exempt`() {
        zero.copy(hasEnded = true).contradicts(later) shouldBe false
        zero.copy(isValid = false).contradicts(later) shouldBe false
    }
}
