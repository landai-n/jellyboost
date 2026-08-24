package dev.jellyboost.player.music

import dev.jellyboost.core.common.music.MusicRepeatMode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Records what it was told and emits what a test wants it to. Everything below the real port is
 * Media3, which cannot be constructed off a device — this stands in so the controller's plain
 * sequencing logic can still get a plain test.
 */
internal class FakeMusicPlayerPort : MusicPlayerPort {
    private val _events =
        MutableSharedFlow<MusicPlayerEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val events: Flow<MusicPlayerEvent> = _events

    val calls = mutableListOf<String>()

    var queue: List<MusicQueueEntry> = emptyList()
        private set
    var shuffleEnabled: Boolean = false
        private set
    var repeatMode: MusicRepeatMode = MusicRepeatMode.OFF
        private set
    var released = false
        private set
    var stopped = false
        private set

    /** What [snapshot] answers; a test moves it to stand in for the player advancing. */
    var currentSnapshot = MusicPortSnapshot()

    suspend fun emit(event: MusicPlayerEvent) {
        _events.emit(event)
    }

    override fun setQueue(
        entries: List<MusicQueueEntry>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        calls += "setQueue(${entries.size}, $startIndex, $startPositionMs, $playWhenReady)"
        queue = entries
        released = false
        currentSnapshot =
            currentSnapshot.copy(
                currentItemIndex = startIndex,
                positionMs = startPositionMs,
                mediaItemCount = entries.size,
            )
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
    }

    override fun next() {
        calls += "next"
    }

    override fun previous() {
        calls += "previous"
    }

    override fun seekToItem(index: Int) {
        calls += "seekToItem($index)"
    }

    override fun removeItem(index: Int) {
        calls += "removeItem($index)"
        queue = queue.toMutableList().apply { removeAt(index) }
        currentSnapshot = currentSnapshot.copy(mediaItemCount = queue.size)
    }

    override fun moveItem(
        from: Int,
        to: Int,
    ) {
        calls += "moveItem($from, $to)"
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        calls += "setShuffleEnabled($enabled)"
        shuffleEnabled = enabled
    }

    override fun setRepeatMode(mode: MusicRepeatMode) {
        calls += "setRepeatMode($mode)"
        repeatMode = mode
    }

    override fun snapshot(): MusicPortSnapshot = currentSnapshot

    override fun retryPrepare() {
        calls += "retryPrepare"
    }

    override fun release() {
        calls += "release"
        released = true
        currentSnapshot = currentSnapshot.copy(mediaItemCount = 0)
    }

    override fun stopAndRelease() {
        calls += "stopAndRelease"
        released = true
        stopped = true
        currentSnapshot = currentSnapshot.copy(mediaItemCount = 0)
    }
}
