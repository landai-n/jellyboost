package dev.jellyfinnative.player.ui

import app.cash.turbine.test
import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.segments.SegmentSkipDecision
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaybackPositionTracker] — the fast half of the player's state, which the audit's
 * PERF-04 finding moved out of `PlayerUiState`.
 *
 * The flow it publishes is the thing under test: a tick must move the position and nothing else, so
 * that the control surface around the scrubber has nothing to recompose for.
 */
class PlaybackPositionTrackerTest {
    private val tracker = PlaybackPositionTracker()

    @Test
    fun `a tick publishes the player's position and buffer`() =
        runTest {
            tracker.position.test {
                awaitItem() shouldBe PlaybackPosition()

                tracker.onTick(PlaybackSnapshot(positionMs = 60_000L, bufferedMs = 90_000L), emptyList(), emptyMap())

                awaitItem() shouldBe PlaybackPosition(positionMs = 60_000L, bufferedMs = 90_000L)
            }
        }

    @Test
    fun `a tick at the same position emits nothing`() =
        runTest {
            val snapshot = PlaybackSnapshot(positionMs = 60_000L, bufferedMs = 90_000L)
            tracker.onTick(snapshot, emptyList(), emptyMap())

            tracker.position.test {
                awaitItem() shouldBe PlaybackPosition(positionMs = 60_000L, bufferedMs = 90_000L)

                // A paused player ticks with an unchanged reading twice a second; conflating it is
                // what keeps the scrubber from recomposing for nothing.
                tracker.onTick(snapshot, emptyList(), emptyMap())

                expectNoEvents()
            }
        }

    @Test
    fun `a seek moves the bar without waiting for the next tick`() =
        runTest {
            tracker.onSeekTo(120_000L)

            tracker.position.value.positionMs shouldBe 120_000L
        }

    @Test
    fun `opening a session restarts the bar at the resume position and clears the buffer`() =
        runTest {
            tracker.onTick(PlaybackSnapshot(positionMs = 60_000L, bufferedMs = 90_000L), emptyList(), emptyMap())

            tracker.onSessionOpened(positionMs = 1_200L)

            // A re-resolve buffers from scratch; keeping the old buffer would draw a bar that is
            // already ahead of a stream that has not started.
            tracker.position.value shouldBe PlaybackPosition(positionMs = 1_200L)
        }

    // ---- the segment decision the tick carries -----------------------------------------------------

    @Test
    fun `a tick inside an intro offers the skip`() {
        val decision = tracker.onTick(PlaybackSnapshot(positionMs = 60_000L), listOf(INTRO), showButton())

        decision.shouldBeInstanceOf<SegmentSkipDecision.Offer>().segment shouldBe INTRO
    }

    @Test
    fun `a tick outside every segment offers nothing`() {
        val decision = tracker.onTick(PlaybackSnapshot(positionMs = 600_000L), listOf(INTRO), showButton())

        decision shouldBe SegmentSkipDecision.None
    }

    @Test
    fun `a new session forgets what the previous one auto-skipped`() {
        val modes = mapOf(MediaSegmentKind.INTRO to SegmentSkipMode.AUTO_SKIP)
        tracker.onTick(PlaybackSnapshot(positionMs = 35_000L), listOf(INTRO), modes)

        tracker.onSessionOpened(positionMs = 0L)
        val decision = tracker.onTick(PlaybackSnapshot(positionMs = 35_000L), listOf(INTRO), modes)

        // Auto-skip fires once per segment *per session*; a re-resolve is a new session, and an
        // intro the user has not seen yet must still be skipped.
        decision.shouldBeInstanceOf<SegmentSkipDecision.AutoSkip>()
    }

    private fun showButton() = mapOf(MediaSegmentKind.INTRO to SegmentSkipMode.SHOW_BUTTON)

    private companion object {
        /** An intro from 30 s to 2 min — long enough to be worth a button. */
        val INTRO = MediaSegment(MediaSegmentKind.INTRO, startMs = 30_000L, endMs = 120_000L)
    }
}
