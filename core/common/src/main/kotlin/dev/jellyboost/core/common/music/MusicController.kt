package dev.jellyboost.core.common.music

import dev.jellyboost.core.common.model.JellyfinItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the rest of the app may know and ask about the music queue.
 *
 * Follows the `SyncPlaySession` precedent, for the same reason: the queue, the player
 * and the reporting all live in `:player`, and `:feature:*` modules must not depend on it. This
 * interface is published from `:core:common`, implemented by `MusicPlaybackController` and
 * Hilt-bound in `:player`, so `:feature:music`'s screens and `:app`'s mini-player can drive
 * playback without any feature ever seeing the player module.
 *
 * ### Why the transport verbs are not `suspend`
 * Every one of them is a *fire-and-forget intent* handed to the controller's own confined scope —
 * a tap on a next button must not make the caller wait for a reporting round trip. [play] is the
 * exception: it resolves a whole queue (a downloads lookup per track) before anything can be
 * handed to the player, and a caller that wants to know when the queue is actually up — the browse
 * screens' spinner — needs to be able to await it.
 *
 * ### Refusals and failures
 * Neither is an exception and neither is state: they are one-shot facts about an *attempt*, which
 * is what [messages] is for. In a SyncPlay group [play] refuses outright and says so
 * ([MusicMessage.RefusedInSyncPlayGroup]) — SyncPlay ⊕ music are mutually exclusive, the same shape
 * as the Cast ⊕ SyncPlay precedent.
 */
interface MusicController {
    /** The queue and its transport state, or [MusicPlaybackState.Idle] when nothing is loaded. */
    val state: StateFlow<MusicPlaybackState>

    /**
     * One-shot notices about an attempt: a refusal, a track that could not be resolved.
     *
     * Deliberately not part of [state]: a message is consumed once by whoever is on screen, while
     * state is re-read by everything that draws. Hot and buffered, so a message emitted while no
     * screen is collecting is dropped rather than replayed into a later, unrelated screen.
     */
    val messages: Flow<MusicMessage>

    /**
     * Replaces the queue with [queue] and starts playing at [startIndex].
     *
     * @param shuffled `true` starts the queue in shuffle order — what a "Shuffle" button means,
     *   as opposed to setting the mode on a queue that is already playing.
     * @param startPositionMs where playback starts within the entry at [startIndex], in
     *   milliseconds. `0` for every ordinary track tap; Home's *Continue Listening* row is the one
     *   caller that resumes mid-track, from `JellyfinItem.userData.playbackPositionTicks`.
     *   Ignored for every entry but the first — a queue has one start position, not one
     *   per track.
     * @return `true` when the queue was handed to the player; `false` when the attempt was refused
     *   or nothing in it could be resolved, in which case a [MusicMessage] explains why.
     */
    suspend fun play(
        queue: List<JellyfinItem>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        startPositionMs: Long = 0L,
    ): Boolean

    /** Pauses if playing, resumes if paused. A no-op while [MusicPlaybackState.Idle]. */
    fun togglePlayPause()

    /** Advances to the next track, obeying shuffle and repeat. */
    fun next()

    /**
     * Restarts the current track, or steps back to the previous one when it has barely started —
     * ExoPlayer's own `seekToPrevious` rule, which is what every music player does.
     */
    fun previous()

    /** Seeks within the current track. */
    fun seekTo(positionMs: Long)

    fun setShuffle(enabled: Boolean)

    /** OFF → ALL → ONE → OFF, the order the notification and the now-playing button cycle in. */
    fun cycleRepeat()

    /** Plays the queue entry at [index] — a tap in the queue sheet. */
    fun jumpTo(index: Int)

    /** Drops the queue entry at [index]. Removing the playing one advances to the next. */
    fun removeAt(index: Int)

    /** Reorders the queue, a drag in the queue sheet. */
    fun moveItem(
        from: Int,
        to: Int,
    )

    /** Ends the session: final stop report, player and notification gone, back to [Idle]. */
    fun stop()
}

/** The music queue as everything above `:player` sees it. */
sealed interface MusicPlaybackState {
    /** No queue: nothing plays, no mini-player, no notification. */
    data object Idle : MusicPlaybackState

    /**
     * A loaded queue, playing or paused.
     *
     * [positionMs] ticks about once a second while playing — coarse on purpose, since every
     * consumer of it draws a progress bar or highlights a lyric line, and neither needs more.
     *
     * The state stays `Active` when the queue runs out (paused on the last track) rather than
     * falling back to [Idle]: that is what every music player does, and it is what lets the user
     * press play again on what they were just listening to.
     *
     * @param parked `true` while the queue has handed the shared player to video and survives
     *   only as a paused snapshot. The mini-player keeps drawing a parked queue — that is the
     *   resume affordance — but surfaces tied to the *live* media session (the notification's
     *   shuffle/repeat buttons) must not: while parked, the session belongs to the film.
     */
    data class Active(
        val queue: List<JellyfinItem>,
        val currentIndex: Int,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: MusicRepeatMode,
        val parked: Boolean = false,
    ) : MusicPlaybackState {
        /** The track playing now, or `null` if the queue is somehow empty. */
        val currentItem: JellyfinItem? get() = queue.getOrNull(currentIndex)
    }
}

/** Repeat, in the three states a music player offers. */
enum class MusicRepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    /** OFF → ALL → ONE → OFF. */
    val next: MusicRepeatMode
        get() =
            when (this) {
                OFF -> ALL
                ALL -> ONE
                ONE -> OFF
            }
}

/** A one-shot notice from the controller, for a snackbar. */
sealed interface MusicMessage {
    /** SyncPlay ⊕ music are mutually exclusive. */
    data object RefusedInSyncPlayGroup : MusicMessage

    /** One track could not be resolved and was dropped from the queue. */
    data class TrackUnavailable(
        val itemName: String,
    ) : MusicMessage

    /** Nothing in the queue could be resolved, so nothing was handed to the player. */
    data object QueueUnavailable : MusicMessage

    /** The player itself failed on the current track. */
    data class PlaybackFailed(
        val itemName: String,
    ) : MusicMessage

    /**
     * "Start radio" could not build a queue: the Instant Mix fetch failed, or the
     * server answered with nothing to play. [itemName] is the seed — the album, artist or track
     * radio was started from — not a track inside the (non-existent) mix.
     */
    data class RadioFailed(
        val itemName: String,
    ) : MusicMessage
}
