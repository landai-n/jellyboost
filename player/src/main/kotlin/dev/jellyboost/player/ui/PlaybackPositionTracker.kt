package dev.jellyboost.player.ui

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.segments.SegmentSkipController
import dev.jellyboost.player.segments.SegmentSkipDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deliberately a separate flow from [PlayerUiState]: folding a value that changes twice a second into
 * the state the whole control surface reads would recompose all of it on every tick.
 *
 * One instance per playback session, like the [SegmentSkipController] it owns.
 */
internal class PlaybackPositionTracker(
    private val segmentSkip: SegmentSkipController = SegmentSkipController(),
) {
    private val _position = MutableStateFlow(PlaybackPosition())

    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    /** @return the segment decision for the caller to act on; this class never seeks. */
    fun onTick(
        snapshot: PlaybackSnapshot,
        segments: List<MediaSegment>,
        skipModes: Map<MediaSegmentKind, SegmentSkipMode>,
    ): SegmentSkipDecision {
        _position.value = PlaybackPosition(positionMs = snapshot.positionMs, bufferedMs = snapshot.bufferedMs)
        return segmentSkip.decide(snapshot.positionMs, segments, skipModes)
    }

    /** Published without waiting for the next tick, or the thumb springs back for up to 500 ms. */
    fun onSeekTo(positionMs: Long) {
        _position.update { it.copy(positionMs = positionMs) }
    }

    /** Call for a new item *and* for the same one re-negotiated. */
    fun onSessionOpened(positionMs: Long) {
        segmentSkip.reset()
        _position.value = PlaybackPosition(positionMs = positionMs)
    }
}
