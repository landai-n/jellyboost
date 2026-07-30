package dev.jellyfinnative.player.ui

import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.segments.SegmentSkipController
import dev.jellyfinnative.player.segments.SegmentSkipDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the 500 ms player tick produces, kept away from [PlayerUiState].
 *
 * Extracted from `PlayerViewModel` (audit ARCH-10) and the home of the audit's PERF-04 fix. Position
 * used to ride inside `PlayerUiState`, which is one `StateFlow` read at screen scope: a value that
 * changes twice a second inside an object the whole control surface reads meant the top bar, the
 * transport row, the picker buttons and the sheet host all recomposed twice a second for a number
 * only the scrubber and the clock display. Splitting the flow is what makes the rest of the state
 * conflate to nothing between ticks.
 *
 * The segment check rides the same tick rather than adding a second one — it needs exactly the same
 * information, twice a second is far more often than a segment boundary moves, and a skip button
 * that appears half a second late is a skip button nobody notices is late.
 *
 * One instance per playback session, like the [SegmentSkipController] it owns.
 */
internal class PlaybackPositionTracker(
    private val segmentSkip: SegmentSkipController = SegmentSkipController(),
) {
    private val _position = MutableStateFlow(PlaybackPosition())

    /** The fast half of the player's state; read only by the scrubber and the clock. */
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    /**
     * One reading of the player.
     *
     * @return what the segment rules make of [snapshot], for the caller to act on — seeking is the
     *   player's business, not this class's.
     */
    fun onTick(
        snapshot: PlaybackSnapshot,
        segments: List<MediaSegment>,
        skipModes: Map<MediaSegmentKind, SegmentSkipMode>,
    ): SegmentSkipDecision {
        _position.value = PlaybackPosition(positionMs = snapshot.positionMs, bufferedMs = snapshot.bufferedMs)
        return segmentSkip.decide(snapshot.positionMs, segments, skipModes)
    }

    /**
     * Moves the seek bar to where a seek has just put playback.
     *
     * Published without waiting for the next tick so the thumb does not spring back to the old
     * position for up to half a second after the user lets go of it.
     */
    fun onSeekTo(positionMs: Long) {
        _position.update { it.copy(positionMs = positionMs) }
    }

    /** Starts a new playback session at [positionMs] — a new item, or the same one re-negotiated. */
    fun onSessionOpened(positionMs: Long) {
        segmentSkip.reset()
        _position.value = PlaybackPosition(positionMs = positionMs)
    }
}
