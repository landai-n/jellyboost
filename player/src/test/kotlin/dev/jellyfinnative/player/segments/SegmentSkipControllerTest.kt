package dev.jellyfinnative.player.segments

import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SegmentSkipController].
 *
 * The auto-skip loop is the bug this class exists to prevent: without the "once per segment" rule,
 * a user who seeks back into an intro is skipped straight back out, and again, and again — a player
 * that cannot be made to play the thing the user is asking for. It is also the one behaviour nobody
 * would find by clicking around, because it only shows up when someone deliberately rewinds.
 */
class SegmentSkipControllerTest {
    private val controller = SegmentSkipController()

    private val intro = MediaSegment(MediaSegmentKind.INTRO, startMs = 30_000L, endMs = 120_000L)
    private val outro = MediaSegment(MediaSegmentKind.OUTRO, startMs = 3_500_000L, endMs = 3_600_000L)
    private val segments = listOf(intro, outro)

    @Test
    fun `offers nothing outside every segment`() {
        controller.decide(10_000L, segments, showBoth()) shouldBe SegmentSkipDecision.None
    }

    @Test
    fun `offers the intro while playback is inside it`() {
        val decision = controller.decide(60_000L, segments, showBoth())

        decision.shouldBeInstanceOf<SegmentSkipDecision.Offer>().segment shouldBe intro
    }

    @Test
    fun `offers the outro while playback is inside it`() {
        val decision = controller.decide(3_550_000L, segments, showBoth())

        decision.shouldBeInstanceOf<SegmentSkipDecision.Offer>().segment shouldBe outro
    }

    @Test
    fun `a type set to off is invisible even in the middle of one`() {
        val modes =
            mapOf(
                MediaSegmentKind.INTRO to SegmentSkipMode.OFF,
                MediaSegmentKind.OUTRO to SegmentSkipMode.SHOW_BUTTON,
            )

        controller.decide(60_000L, segments, modes) shouldBe SegmentSkipDecision.None
    }

    @Test
    fun `a kind with no preference at all is off`() {
        controller.decide(60_000L, segments, emptyMap()) shouldBe SegmentSkipDecision.None
    }

    @Test
    fun `auto-skip jumps to the end of the segment`() {
        val decision = controller.decide(35_000L, segments, autoSkipIntro())

        decision.shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>().segment.endMs shouldBe 120_000L
    }

    @Test
    fun `auto-skip fires once, then only offers - a user who seeks back is not fought`() {
        val modes = autoSkipIntro()

        controller.decide(35_000L, segments, modes).shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>()

        // The user seeks back into the intro they were just carried out of.
        repeat(3) {
            controller
                .decide(35_000L, segments, modes)
                .shouldBeInstanceOf<SegmentSkipDecision.Offer>()
                .segment shouldBe intro
        }
    }

    @Test
    fun `each segment gets its own auto-skip`() {
        val modes =
            mapOf(
                MediaSegmentKind.INTRO to SegmentSkipMode.AUTO_SKIP,
                MediaSegmentKind.OUTRO to SegmentSkipMode.AUTO_SKIP,
            )

        controller.decide(35_000L, segments, modes).shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>()
        // Skipping the intro must not use up the outro's one shot.
        controller.decide(3_550_000L, segments, modes).shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>()
    }

    @Test
    fun `a new session forgets what it skipped`() {
        val modes = autoSkipIntro()
        controller.decide(35_000L, segments, modes)

        controller.reset()

        controller.decide(35_000L, segments, modes).shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>()
    }

    @Test
    fun `a segment too short to be worth skipping is ignored`() {
        // Half a second: a detection artefact, and a button that would flash for three frames.
        val blink = listOf(MediaSegment(MediaSegmentKind.INTRO, startMs = 1_000L, endMs = 1_500L))

        controller.decide(1_200L, blink, showBoth()) shouldBe SegmentSkipDecision.None
    }

    private fun showBoth() =
        mapOf(
            MediaSegmentKind.INTRO to SegmentSkipMode.SHOW_BUTTON,
            MediaSegmentKind.OUTRO to SegmentSkipMode.SHOW_BUTTON,
        )

    private fun autoSkipIntro() =
        mapOf(
            MediaSegmentKind.INTRO to SegmentSkipMode.AUTO_SKIP,
            MediaSegmentKind.OUTRO to SegmentSkipMode.SHOW_BUTTON,
        )
}
