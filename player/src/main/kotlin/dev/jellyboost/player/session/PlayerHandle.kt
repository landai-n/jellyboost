package dev.jellyboost.player.session

import androidx.media3.common.Player
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The player, as far as [dev.jellyboost.player.ui.PlayerViewModel] is concerned.
 *
 * ExoPlayer cannot be instantiated off a device, so every piece of playback logic that touches it
 * directly becomes untestable. Keeping the ViewModel — where resolution, reporting, fallback and
 * track switching are sequenced — behind this seam is what lets that sequencing be unit tested
 * against a fake, and leaves the real implementation as thin, mechanical glue.
 */
internal interface PlayerHandle {
    /** Player callbacks, already off the ExoPlayer listener thread. */
    val events: Flow<PlayerEvent>

    /**
     * The Media3 player, for attaching a video surface and nothing else.
     *
     * The one hole in this abstraction, and a deliberate one: video has to be rendered into a real
     * `SurfaceView`, and there is no way to express that without the player itself. `null` before
     * the first [prepare], and in tests.
     */
    val player: Player?

    /**
     * Loads [spec] and starts buffering.
     *
     * @param startPositionMs where to seek before playback begins — a resume position, or the
     *   position playback had reached before a re-resolve.
     */
    fun prepare(
        spec: PlaybackMediaItemSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    )

    /**
     * The same load, told which negotiation produced [spec].
     *
     * The overload every caller with a resolved source should use, and the only one that carries
     * enough for a *remote* player: a Cast receiver fetches its own bytes, so it needs what the URL
     * alone does not say — the runtime, the container the server settled on, and the stream indices
     * behind the side-loaded subtitles ([dev.jellyboost.player.cast.CastSpecMapper]).
     *
     * An overload rather than a fourth parameter because the local player genuinely does not need
     * it: the default drops the source and calls [prepare], which is the whole implementation for
     * [ExoPlayerHandle] and for every test double.
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

    /** Reads position, duration and play state in one go. Must be called from the main thread. */
    fun snapshot(): PlaybackSnapshot

    /**
     * Selects an audio track without re-negotiating with the server.
     *
     * @return `false` when the track is not present in the current stream — the caller must then
     *   re-resolve, which is always the case while transcoding because the server only ever sends
     *   the one audio track it was asked for.
     */
    fun selectAudioTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int,
    ): Boolean

    /**
     * Selects a subtitle track locally, or disables subtitles when [jellyfinIndex] is `null`.
     *
     * @return `false` when the subtitle is not available in the current stream and the source has
     *   to be re-resolved (a burned-in subtitle, for instance).
     */
    fun selectSubtitleTrack(
        source: PlaybackMediaSource,
        jellyfinIndex: Int?,
    ): Boolean

    /**
     * Sets the playback rate, where `1f` is normal speed.
     *
     * Session-scoped by design and not persisted, matching jellyfin-web: a speed the user set for
     * one lecture should not silently follow them into the next film (docs/PLAN.md, "M9 Polish" →
     * speed). It therefore has to be re-applied after every re-resolve, since a re-negotiation
     * builds a fresh media item.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Whether [setPlaybackSpeed] would do anything on this player.
     *
     * `true` for anything decoding on this device — ExoPlayer always has a rate — which is why the
     * default is the answer every implementation but one wants. A Cast receiver is the exception:
     * the rate is a *receiver* capability, published per session, and the player screen hides the
     * speed picker rather than offering rows that are silently dropped
     * (docs/notes/chromecast-m12-plan.md, decision 7).
     *
     * Read rather than remembered: while casting the answer belongs to whatever is on the other end
     * of the network, and it is only knowable once that receiver has something loaded.
     */
    val supportsPlaybackSpeed: Boolean get() = true

    /** Stops playback and clears the queued media, leaving the player reusable. */
    fun stop()

    /**
     * Gives back everything the player is holding.
     *
     * [stop] only idles the player: the playback thread, the loaders, the allocator's buffers, the
     * ffmpeg extension renderer and the event listener all stay alive for the rest of the process,
     * and every session that follows adds nothing back but keeps them. This is the call that ends
     * them, and it is the only one that does.
     *
     * Must be idempotent — the session teardown and the media-session service's teardown both reach
     * it, in either order — and must leave the handle usable again, since the next session builds a
     * fresh player lazily.
     */
    fun release()
}

/** The player callbacks the ViewModel reacts to. */
internal sealed interface PlayerEvent {
    /** The player has enough data to play. */
    data object Ready : PlayerEvent

    /** The item played to its end. */
    data object Ended : PlayerEvent

    data class IsPlayingChanged(
        val isPlaying: Boolean,
    ) : PlayerEvent

    /** Tracks appeared or changed — the pickers need rebuilding. */
    data object TracksChanged : PlayerEvent

    /**
     * The decoded video size became known or changed.
     *
     * Picture-in-picture needs it: the floating window is created with the video's aspect ratio, and
     * the only party that knows it is the decoder (docs/PLAN.md, "M9 Polish" → PiP).
     */
    data class VideoSizeChanged(
        val width: Int,
        val height: Int,
    ) : PlayerEvent

    /**
     * Playback failed.
     *
     * @param errorCode the `PlaybackException.errorCode`, which decides whether the decoder
     *   fallback ladder can rescue this.
     */
    data class Error(
        val errorCode: Int,
        val message: String?,
    ) : PlayerEvent
}
