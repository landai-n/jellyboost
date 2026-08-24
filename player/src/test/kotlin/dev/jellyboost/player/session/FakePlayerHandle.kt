package dev.jellyboost.player.session

import androidx.media3.common.Player
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [PlayerHandle] that records what it was asked to do — lets tests exercise resolving,
 * reporting, falling back, and re-resolve sequencing without an ExoPlayer, which cannot exist
 * off a device.
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

    /** How many times [release] was called — the count is what pins its idempotent call sites. */
    var releaseCount = 0
        private set

    var snapshot = PlaybackSnapshot()

    /** SyncPlay's in-group rule claims *no* local playback call was ever made — a count, not a flag. */
    var playCount = 0
        private set

    var pauseCount = 0
        private set

    /** Every position [seekTo] was asked for, oldest first. */
    val seekedToMs = mutableListOf<Long>()

    /** `true` when nothing has touched the transport since the handle was created or [resetCalls]. */
    val hadNoTransportCalls: Boolean
        get() = playCount == 0 && pauseCount == 0 && seekedToMs.isEmpty() && prepared.isEmpty() && !stopped

    /** Forgets recorded calls, so a test can assert about one phase of a longer scenario. */
    fun resetCalls() {
        playCount = 0
        pauseCount = 0
        seekedToMs.clear()
        prepared.clear()
        stopped = false
    }

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
        playCount++
        snapshot = snapshot.copy(isPlaying = true)
    }

    override fun pause() {
        pauseCount++
        snapshot = snapshot.copy(isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        seekedToMs += positionMs
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

    /** Writable so a test can be a receiver with no playback rate; `true` matches every local player. */
    override var supportsPlaybackSpeed: Boolean = true

    override fun stop() {
        stopped = true
    }

    override fun release() {
        releaseCount++
    }

    data class PreparedItem(
        val spec: PlaybackMediaItemSpec,
        val startPositionMs: Long,
        val playWhenReady: Boolean,
    )
}
