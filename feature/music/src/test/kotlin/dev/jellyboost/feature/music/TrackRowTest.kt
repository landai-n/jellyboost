package dev.jellyboost.feature.music

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackRowTest {
    @Test
    fun `formats whole minutes and seconds`() {
        formatTrackDuration(3 * 60 * TICKS_PER_SECOND + 45 * TICKS_PER_SECOND) shouldBe "3:45"
    }

    @Test
    fun `pads seconds under ten with a leading zero`() {
        formatTrackDuration(2 * 60 * TICKS_PER_SECOND + 5 * TICKS_PER_SECOND) shouldBe "2:05"
    }

    @Test
    fun `a track under a minute still reads as zero point something`() {
        formatTrackDuration(30 * TICKS_PER_SECOND) shouldBe "0:30"
    }

    @Test
    fun `null runtime formats to nothing`() {
        formatTrackDuration(null).shouldBeNull()
    }

    @Test
    fun `zero or negative runtime formats to nothing`() {
        formatTrackDuration(0L).shouldBeNull()
        formatTrackDuration(-1L).shouldBeNull()
    }

    private companion object {
        const val TICKS_PER_SECOND = 10_000_000L
    }
}
