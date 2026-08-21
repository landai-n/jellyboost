package dev.jellyboost.player.upnext

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.player.segments.MediaSegment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UpNextController].
 *
 * Two rules carry the whole feature and neither is discoverable by clicking around: a card that
 * cannot be dismissed for good is a card sitting over the last minute of every episode, and a
 * fallback window applied to a two-minute extra would cover a sixth of it. Both are pinned here,
 * away from a player.
 */
class UpNextControllerTest {
    private val controller = UpNextController()

    private val outro = MediaSegment(MediaSegmentKind.OUTRO, startMs = 3_500_000L, endMs = 3_600_000L)

    @Test
    fun `stays away for most of the episode`() {
        controller.shouldShow(600_000L, HOUR_MS, outro = null, hasNext = true) shouldBe false
    }

    @Test
    fun `appears when the outro starts`() {
        controller.shouldShow(3_499_999L, HOUR_MS, outro, hasNext = true) shouldBe false
        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe true
    }

    @Test
    fun `falls back to the last thirty seconds when the item has no outro`() {
        controller.shouldShow(HOUR_MS - 30_001L, HOUR_MS, outro = null, hasNext = true) shouldBe false
        controller.shouldShow(HOUR_MS - 30_000L, HOUR_MS, outro = null, hasNext = true) shouldBe true
    }

    @Test
    fun `the outro wins over the arithmetic, even when it starts much earlier`() {
        // The fallback would have waited until 59:30; the server says the episode is over at 58:20.
        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe true
    }

    @Test
    fun `a short item never reaches the fallback window`() {
        // A minute is the floor: on anything at or below it, "the last thirty seconds" is half the
        // item, and a card covering half of something is covering it rather than following it.
        controller.shouldShow(59_999L, 60_000L, outro = null, hasNext = true) shouldBe false
        controller.shouldShow(60_000L, 60_000L, outro = null, hasNext = true) shouldBe false
    }

    @Test
    fun `a short item with a real outro is still offered one`() {
        val shortOutro = MediaSegment(MediaSegmentKind.OUTRO, startMs = 40_000L, endMs = 50_000L)

        controller.shouldShow(45_000L, 50_000L, shortOutro, hasNext = true) shouldBe true
    }

    @Test
    fun `nothing is offered when there is no next episode`() {
        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = false) shouldBe false
    }

    @Test
    fun `a dismissal lasts the rest of the session`() {
        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe true

        controller.dismiss()

        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe false
        controller.shouldShow(HOUR_MS - 1_000L, HOUR_MS, outro, hasNext = true) shouldBe false
    }

    @Test
    fun `a dismissal does not survive into the next item`() {
        controller.dismiss()

        controller.reset()

        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe true
    }

    @Test
    fun `seeking back out of the window hides the card, and returning shows it again`() {
        // Not a dismissal: the user went back to watch something, and coming forward again should
        // find the offer where they left it.
        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe true
        controller.shouldShow(600_000L, HOUR_MS, outro, hasNext = true) shouldBe false
        controller.shouldShow(outro.startMs + 10_000L, HOUR_MS, outro, hasNext = true) shouldBe true
    }

    @Test
    fun `a dismissal made before the window still holds inside it`() {
        controller.dismiss()

        controller.shouldShow(outro.startMs, HOUR_MS, outro, hasNext = true) shouldBe false
    }

    private companion object {
        /** An hour, which is longer than the fallback window and short enough to read. */
        const val HOUR_MS = 3_600_000L
    }
}
