package dev.jellyfinnative.player.session

import androidx.media3.common.Player
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [PlayerHandle] that records what it was asked to do.
 *
 * Lets `PlayerViewModelTest` exercise the parts of playback that are actually ours — resolving,
 * reporting, falling back, sequencing a re-resolve — without an ExoPlayer, which cannot exist off
 * a device.
 */
internal class FakePlayerHandle : PlayerHandle {
    private val _events =
        MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val events: Flow<PlayerEvent> = _events

    override val player: Player? = null

    /** Every [prepare] call, oldest first. */
    val prepared = mutableListOf<PreparedItem>()

    var stopped = false
        private set

    var snapshot = PlaybackSnapshot()

    /** What [selectAudioTrack] / [selectSubtitleTrack] should answer — `false` forces a re-resolve. */
    var trackSelectionSucceeds = true

    val selectedAudioIndices = mutableListOf<Int>()
    val selectedSubtitleIndices = mutableListOf<Int?>()

    suspend fun emit(event: PlayerEvent) {
        _events.emit(event)
    }

    override fun prepare(
        spec: PlaybackMediaItemSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        prepared += PreparedItem(spec, startPositionMs, playWhenReady)
    }

    override fun play() {
        snapshot = snapshot.copy(isPlaying = true)
    }

    override fun pause() {
        snapshot = snapshot.copy(isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        snapshot = snapshot.copy(positionMs = positionMs)
    }

    override fun snapshot(): PlaybackSnapshot = snapshot

    override fun selectAudioTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int,
    ): Boolean {
        selectedAudioIndices += jellyfinIndex
        return trackSelectionSucceeds
    }

    override fun selectSubtitleTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int?,
    ): Boolean {
        selectedSubtitleIndices += jellyfinIndex
        return trackSelectionSucceeds
    }

    /** Every rate [setPlaybackSpeed] was asked for, oldest first. */
    val playbackSpeeds = mutableListOf<Float>()

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeeds += speed
    }

    override fun stop() {
        stopped = true
    }

    data class PreparedItem(
        val spec: PlaybackMediaItemSpec,
        val startPositionMs: Long,
        val playWhenReady: Boolean,
    )
}
