package dev.jellyboost.player.music

import dev.jellyboost.core.common.music.MusicRepeatMode
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the music queue's orchestration and the player it runs on.
 *
 * `PlayerHandle` is deliberately **not** widened to carry any of this
 * (docs/notes/music-m13-plan.md, key decision 1): a queue on that interface would have to be
 * implemented three times — Exo, Cast, Routing — and would drag casting into M13's scope for a
 * feature the plan explicitly defers. This is a second, narrower seam over the same shared
 * `ExoPlayer`, used only by [MusicPlaybackController], and the reason that controller can be unit
 * tested at all: everything below here is Media3, everything above is plain data.
 *
 * Every call must be made on the main thread — Media3 throws otherwise — which the controller
 * arranges by hopping onto `@MainDispatcher`, exactly as `SyncPlayController` does.
 */
internal interface MusicPlayerPort {
    /**
     * Events the queue's orchestration reacts to.
     *
     * The one that does not exist on `PlayerEvent` is [MusicPlayerEvent.ItemTransition], and it is
     * the reason this flow exists rather than reusing the shared handle's: a track ending and the
     * next one starting is *two server sessions*, and nothing in the video path ever needed to
     * know about it.
     */
    val events: Flow<MusicPlayerEvent>

    /**
     * Replaces the playlist and prepares it.
     *
     * Implicitly claims the player: the listener is attached, the audio attributes become music's,
     * and the media session service is started, all idempotently.
     */
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

    /** Jumps to a queue position — a tap in the queue sheet. */
    fun seekToItem(index: Int)

    fun removeItem(index: Int)

    fun moveItem(
        from: Int,
        to: Int,
    )

    fun setShuffleEnabled(enabled: Boolean)

    fun setRepeatMode(mode: MusicRepeatMode)

    /** Where the player is right now; cheap enough for a one-second ticker. */
    fun snapshot(): MusicPortSnapshot

    /**
     * Re-prepares the playlist the player already holds, at its current position.
     *
     * The recovery verb for a player error: ExoPlayer parks in `IDLE` after `onPlayerError`, and
     * in that state `play()` is a no-op — `prepare()` is Media3's own documented retry, keeping
     * playlist and position. Distinct from [setQueue], which *replaces* the playlist and would
     * reset the shuffle order.
     */
    fun retryPrepare()

    /**
     * Lets the player go: the playlist is cleared, the listener detached and the video path's
     * audio attributes restored, but the shared player itself is **not** released — whoever is
     * claiming it next is about to prepare on it.
     */
    fun release()

    /** [release], plus stopping the media session service. Ends the session outright. */
    fun stopAndRelease()
}

/**
 * The player's position, as the controller's state and its progress reports need it.
 *
 * @param mediaItemCount how many entries the player's own playlist holds right now. Zero while
 *   the controller's state says `Active` is the signature of a player that was released and
 *   rebuilt underneath the queue (the shared handle does that whenever the playback service is
 *   torn down) — the controller re-prepares from its own state instead of issuing transport
 *   calls into an empty player.
 */
internal data class MusicPortSnapshot(
    val currentItemIndex: Int = 0,
    val currentMediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val mediaItemCount: Int = 0,
)

/** What the player tells the queue's orchestration. */
internal sealed interface MusicPlayerEvent {
    /**
     * The player moved to another entry.
     *
     * @param automatic `true` when the previous track *finished* — the distinction the stop report
     *   needs, because a track played to the end is marked played and one skipped away from keeps
     *   its position.
     */
    data class ItemTransition(
        val index: Int,
        val mediaId: String?,
        val automatic: Boolean,
    ) : MusicPlayerEvent

    data class IsPlayingChanged(
        val isPlaying: Boolean,
    ) : MusicPlayerEvent

    /** The queue ran out. */
    data object Ended : MusicPlayerEvent

    data class Error(
        val code: Int,
        val message: String?,
    ) : MusicPlayerEvent
}
