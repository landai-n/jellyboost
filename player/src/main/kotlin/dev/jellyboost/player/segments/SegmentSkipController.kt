package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.common.model.SegmentSkipMode

/**
 * **Auto-skip fires once per segment.** Seeking back into a segment that was just skipped is the
 * user asking to watch it, so an already-auto-skipped segment is downgraded to a button for the rest
 * of the session — otherwise the user is in a loop they cannot leave without changing the setting.
 *
 * Stateful, and scoped to one playback session; [reset] starts a new one.
 */
internal class SegmentSkipController {
    private val autoSkipped = mutableSetOf<MediaSegment>()

    /** Call when a new item — or a new source for the same item — is opened. */
    fun reset() {
        autoSkipped.clear()
    }

    /** @param modes a kind missing from the map is treated as [SegmentSkipMode.OFF]. */
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
         * Matches jellyfin-android's `MediaSegmentRepository.SKIP_MIN_DURATION`: a sub-second
         * segment is a detection artefact, not something to offer a button for.
         */
        const val MIN_SKIPPABLE_MS = 1_000L
    }
}

internal sealed interface SegmentSkipDecision {
    data object None : SegmentSkipDecision

    data class Offer(
        val segment: MediaSegment,
    ) : SegmentSkipDecision

    /** Seek to the segment's end immediately; only ever emitted once per segment. */
    data class AutoSkip(
        val segment: MediaSegment,
    ) : SegmentSkipDecision
}
