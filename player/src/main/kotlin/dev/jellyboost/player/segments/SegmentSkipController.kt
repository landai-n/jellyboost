package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.common.model.SegmentSkipMode

/**
 * Decides what should happen at a given playback position, given the item's segments and the user's
 * per-type preference (docs/PLAN.md, "M9 Polish" → segment skip).
 *
 * All of the feature's judgement lives here rather than in the ViewModel or the composable, because
 * all of it is a pure function of position plus one piece of remembered state — and that one piece
 * is the whole reason the feature needs a class at all:
 *
 * > **auto-skip fires once per segment.** A user who seeks back into an intro they were just
 * > skipped out of is telling the player, unambiguously, that they want to watch it. Re-skipping
 * > would put them in a loop they cannot escape without turning the setting off. So a segment that
 * > has already been auto-skipped is downgraded to a *button* for the rest of the session: still
 * > one tap away, never automatic again.
 *
 * The controller is stateful and belongs to one playback session; [reset] starts a new one. It is
 * constructed by `PlayerViewModel` rather than injected, because "one per playback session" is
 * exactly the ViewModel's own lifetime and a Hilt scope would only be a longer way to say so.
 */
internal class SegmentSkipController {
    /** Segments this session has already jumped over on its own. */
    private val autoSkipped = mutableSetOf<MediaSegment>()

    /** Forgets what has been skipped — called when a new item, or a new source, is opened. */
    fun reset() {
        autoSkipped.clear()
    }

    /**
     * @param modes the user's preference per segment kind; a kind missing from the map is treated
     *   as [SegmentSkipMode.OFF].
     * @return what the player should do at [positionMs].
     */
    fun decide(
        positionMs: Long,
        segments: List<MediaSegment>,
        modes: Map<MediaSegmentKind, SegmentSkipMode>,
    ): SegmentSkipDecision {
        val segment =
            segments.firstOrNull { candidate ->
                candidate.contains(positionMs) &&
                    candidate.durationMs >= MIN_SKIPPABLE_MS &&
                    modes[candidate.kind].orOff() != SegmentSkipMode.OFF
            } ?: return SegmentSkipDecision.None

        val autoSkip =
            modes[segment.kind] == SegmentSkipMode.AUTO_SKIP && autoSkipped.add(segment)

        return when {
            autoSkip -> SegmentSkipDecision.AutoSkip(segment)
            else -> SegmentSkipDecision.Offer(segment)
        }
    }

    private fun SegmentSkipMode?.orOff(): SegmentSkipMode = this ?: SegmentSkipMode.OFF

    private companion object {
        /**
         * Shorter than this and a skip is not worth offering.
         *
         * Matches jellyfin-android's `MediaSegmentRepository.SKIP_MIN_DURATION`: a sub-second
         * segment is a detection artefact, and a button that flashes for three frames is noise.
         */
        const val MIN_SKIPPABLE_MS = 1_000L
    }
}

/** What [SegmentSkipController] concluded about the current position. */
internal sealed interface SegmentSkipDecision {
    /** Nothing to offer — outside every actionable segment. */
    data object None : SegmentSkipDecision

    /** Inside [segment]: draw the skip button, and do nothing unless it is pressed. */
    data class Offer(
        val segment: MediaSegment,
    ) : SegmentSkipDecision

    /** Inside [segment] for the first time under AUTO_SKIP: seek to its end, now. */
    data class AutoSkip(
        val segment: MediaSegment,
    ) : SegmentSkipDecision
}
