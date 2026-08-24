package dev.jellyboost.core.common.music

import dev.jellyboost.core.common.model.JellyfinItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the rest of the app may know and ask about the music queue.
 *
 * Published from `:core:common` and implemented in `:player` so `:feature:*` never depends on the player
 * module (the `SyncPlaySession` precedent).
 *
 * The transport verbs are not `suspend` because each is a fire-and-forget intent handed to the controller's
 * own scope; [play] is the exception, since a caller may need to await a resolved queue.
 *
 * SyncPlay ⊕ music are mutually exclusive: in a group [play] refuses and says so via [messages].
 */
interface MusicController {
    val state: StateFlow<MusicPlaybackState>

    /**
     * Hot and buffered, and deliberately not part of [state]: a message emitted while no screen is
     * collecting is dropped rather than replayed into a later, unrelated screen.
     */
    val messages: Flow<MusicMessage>

    /**
     * @param startPositionMs ignored for every entry but the first — a queue has one start position, not one
     *   per track. `0` for an ordinary track tap; Home's *Continue Listening* row is the caller that resumes.
     * @return `false` when the attempt was refused or nothing could be resolved, with a [MusicMessage] to say why.
     */
    suspend fun play(
        queue: List<JellyfinItem>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        startPositionMs: Long = 0L,
    ): Boolean

    /** A no-op while [MusicPlaybackState.Idle]. */
    fun togglePlayPause()

    fun next()

    /** Restarts the current track, or steps back when it has barely started — ExoPlayer's `seekToPrevious` rule. */
    fun previous()

    fun seekTo(positionMs: Long)

    fun setShuffle(enabled: Boolean)

    /** OFF → ALL → ONE → OFF, the order the notification and the now-playing button cycle in. */
    fun cycleRepeat()

    fun jumpTo(index: Int)

    /** Removing the playing entry advances to the next. */
    fun removeAt(index: Int)

    fun moveItem(
        from: Int,
        to: Int,
    )

    fun stop()
}

sealed interface MusicPlaybackState {
    data object Idle : MusicPlaybackState

    /**
     * [positionMs] ticks about once a second — coarse on purpose; every consumer draws a progress bar or
     * highlights a lyric line. The state stays `Active` when the queue runs out (paused on the last track)
     * rather than falling back to [Idle], so the user can press play again on what they were listening to.
     *
     * @param parked `true` while the queue has handed the shared player to video and survives only as a
     *   paused snapshot. The mini-player keeps drawing a parked queue — that is the resume affordance — but
     *   surfaces tied to the *live* media session must not: while parked, the session belongs to the film.
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
        val currentItem: JellyfinItem? get() = queue.getOrNull(currentIndex)
    }
}

enum class MusicRepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    val next: MusicRepeatMode
        get() =
            when (this) {
                OFF -> ALL
                ALL -> ONE
                ONE -> OFF
            }
}

sealed interface MusicMessage {
    data object RefusedInSyncPlayGroup : MusicMessage

    data class TrackUnavailable(
        val itemName: String,
    ) : MusicMessage

    data object QueueUnavailable : MusicMessage

    data class PlaybackFailed(
        val itemName: String,
    ) : MusicMessage

    /** [itemName] is the seed radio was started from — the album, artist or track — not a track inside the mix. */
    data class RadioFailed(
        val itemName: String,
    ) : MusicMessage
}
