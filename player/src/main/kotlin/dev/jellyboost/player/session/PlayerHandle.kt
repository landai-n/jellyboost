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

internal sealed interface PlayerEvent {
    data object Ready : PlayerEvent

    data object Ended : PlayerEvent

    data class IsPlayingChanged(
        val isPlaying: Boolean,
    ) : PlayerEvent

    data object TracksChanged : PlayerEvent

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
