package dev.jellyboost.player.music

import dev.jellyboost.core.common.music.MusicRepeatMode
import kotlinx.coroutines.flow.Flow

/** Every call must be made on the main thread; Media3 throws otherwise. */
internal interface MusicPlayerPort {
    val events: Flow<MusicPlayerEvent>

    /** Also claims the shared player: attaches the listener, swaps audio attributes, starts the session service. */
    fun setQueue(
        entries: List<MusicQueueEntry>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    )

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun next()

    fun previous()

    fun seekToItem(index: Int)

    fun removeItem(index: Int)

    fun moveItem(
        from: Int,
        to: Int,
    )

    fun setShuffleEnabled(enabled: Boolean)

    fun setRepeatMode(mode: MusicRepeatMode)

    fun snapshot(): MusicPortSnapshot

    /**
     * After `onPlayerError` the player parks in `IDLE`, where `play()` is a no-op; only `prepare()` recovers.
     * Not [setQueue], which replaces the playlist and resets the shuffle order.
     */
    fun retryPrepare()

    /** Clears and detaches, but does **not** release the shared player — the next claimant prepares on it. */
    fun release()

    /** [release], plus stopping the media session service. */
    fun stopAndRelease()
}

/**
 * @param mediaItemCount zero while the controller's state is `Active` means the shared player was released and
 *   rebuilt underneath the queue; re-prepare instead of issuing transport calls into it.
 */
internal data class MusicPortSnapshot(
    val currentItemIndex: Int = 0,
    val currentMediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val mediaItemCount: Int = 0,
)

internal sealed interface MusicPlayerEvent {
    /**
     * @param automatic `true` when the previous track finished: the stop report marks it played rather than
     *   saving a position.
     */
    data class ItemTransition(
        val index: Int,
        val mediaId: String?,
        val automatic: Boolean,
    ) : MusicPlayerEvent

    data class IsPlayingChanged(
        val isPlaying: Boolean,
    ) : MusicPlayerEvent

    data object Ended : MusicPlayerEvent

    data class Error(
        val code: Int,
        val message: String?,
    ) : MusicPlayerEvent
}
