package dev.jellyboost.player.session

import androidx.media3.common.Player
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The player, as far as [dev.jellyboost.player.ui.PlayerViewModel] is concerned.
 *
 * ExoPlayer cannot be instantiated off a device: implementations stay thin glue so the ViewModel's
 * sequencing remains unit-testable against a fake.
 */
internal interface PlayerHandle {
    /** Player callbacks, already off the ExoPlayer listener thread. */
    val events: Flow<PlayerEvent>

    /**
     * The Media3 player, for attaching a video surface and nothing else.
     *
     * `null` before the first [prepare], and in tests.
     */
    val player: Player?

    /** @param startPositionMs seek target before playback begins (resume, or position before a re-resolve). */
    fun prepare(
        spec: PlaybackMediaItemSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    )

    /**
     * The overload every caller with a resolved source should use: a remote (Cast) player fetches its
     * own bytes and needs what the URL does not say — runtime, container, side-loaded stream indices.
     */
    fun prepare(
        source: PlaybackMediaSource,
        spec: PlaybackMediaItemSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) = prepare(spec, startPositionMs, playWhenReady)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Must be called from the main thread. */
    fun snapshot(): PlaybackSnapshot

    /**
     * What the player has been asked to do, as opposed to what it is doing: `true` while it is
     * playing **or buffering toward playing**, `false` once paused. This, not
     * [PlaybackSnapshot.isPlaying], is what a play/pause toggle reverses — a player waiting for data
     * is not playing, yet a tap on it means "pause". Unlike [snapshot] it stays readable while a
     * receiver's reading is [invalid][PlaybackSnapshot.isValid]. `false` before the first [prepare].
     * Main thread only.
     */
    val playWhenReady: Boolean

    /**
     * `true` only when the player reports **from its own state, not from [playWhenReady]**, that it
     * has settled paused: neither playing nor buffering. A local player's intent *is* its state, so
     * the default is `false`. A Cast receiver can disagree with the intent `CastPlayer` masks — a
     * `play` dropped while a load was in flight leaves `playWhenReady` `true` over a paused
     * receiver — and this is what [togglePlayWhenReady] breaks that tie with. Main thread only.
     */
    val isSettledPaused: Boolean get() = false

    /**
     * @return `false` when the track is absent from the current stream and the caller must re-resolve
     *   — always the case while transcoding: the server sends only the audio track it was asked for.
     */
    fun selectAudioTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int,
    ): Boolean

    /**
     * `null` [jellyfinIndex] disables subtitles.
     *
     * @return `false` when the subtitle needs the source re-resolved (a burned-in subtitle, say).
     */
    fun selectSubtitleTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int?,
    ): Boolean

    /**
     * `1f` is normal speed. Session-scoped and not persisted (matching jellyfin-web), so it must be
     * re-applied after every re-resolve: a re-negotiation builds a fresh media item.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Whether [setPlaybackSpeed] would do anything. Read, never remembered: while casting this is a
     * receiver capability, knowable only once that receiver has something loaded.
     */
    val supportsPlaybackSpeed: Boolean get() = true

    /** Idles the player but keeps its resources; the handle stays reusable. */
    fun stop()

    /**
     * Releases the playback thread, loaders, allocator buffers and renderers — [stop] does not.
     *
     * Must be idempotent (session teardown and the media-session service both reach it, in either
     * order) and must leave the handle usable again: the next session builds a fresh player lazily.
     */
    fun release()
}

/**
 * The one play/pause rule, shared by the player screen and the casting bar so the two cannot
 * disagree: reverse the player's **intent** ([PlayerHandle.playWhenReady]) — except that a player
 * meaning to play while it reports itself [settled paused][PlayerHandle.isSettledPaused] is sent
 * `play`. Pausing it would be a no-op, and the button would look dead.
 */
internal fun PlayerHandle.togglePlayWhenReady() {
    if (playWhenReady && !isSettledPaused) pause() else play()
}

internal sealed interface PlayerEvent {
    data object Ready : PlayerEvent

    data object Ended : PlayerEvent

    data class IsPlayingChanged(
        val isPlaying: Boolean,
    ) : PlayerEvent

    data object TracksChanged : PlayerEvent

    /**
     * `true` while the player is waiting for data **and means to play once it has it**
     * (`STATE_BUFFERING` with `playWhenReady`) — a paused player filling its buffer is not "buffering"
     * to anyone looking at it. Emitted on change only, by the shared listener for both players: a
     * receiver can sit here for minutes, and a local rebuffer is the same state.
     */
    data class Buffering(
        val isBuffering: Boolean,
    ) : PlayerEvent

    /**
     * Cast only: the receiver has stopped holding the item this app loaded on it — stopped from the
     * television, unloaded after an idle timeout, or replaced by another sender — while the session
     * stays connected. Armed only once the receiver has been seen holding the item since the last
     * load, so the few seconds a load takes to appear never raise it. The handle only *notices*;
     * `CastSessionCoordinator` decides, after a grace period, that the item is really gone.
     *
     * @param lastHeld the last reading taken while the receiver still held the item — always
     *   [valid][PlaybackSnapshot.isValid], so it can carry a resume position.
     */
    data class RemoteItemMissing(
        val lastHeld: PlaybackSnapshot,
    ) : PlayerEvent

    /** Cast only: whatever raised [RemoteItemMissing] is over — the item came back, or a new load replaced it. */
    data object RemoteItemMissingCleared : PlayerEvent

    /** Picture-in-picture needs this: the floating window is created with the decoded aspect ratio. */
    data class VideoSizeChanged(
        val width: Int,
        val height: Int,
    ) : PlayerEvent

    /** @param errorCode `PlaybackException.errorCode`, which decides whether the fallback ladder applies. */
    data class Error(
        val errorCode: Int,
        val message: String?,
    ) : PlayerEvent
}
