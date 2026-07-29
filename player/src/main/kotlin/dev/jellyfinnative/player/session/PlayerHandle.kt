package dev.jellyfinnative.player.session

import androidx.media3.common.Player
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The player, as far as [dev.jellyfinnative.player.ui.PlayerViewModel] is concerned.
 *
 * ExoPlayer cannot be instantiated off a device, so every piece of playback logic that touches it
 * directly becomes untestable. Keeping the ViewModel — where resolution, reporting, fallback and
 * track switching are sequenced — behind this seam is what lets that sequencing be unit tested
 * against a fake, and leaves the real implementation as thin, mechanical glue.
 */
interface PlayerHandle {
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

    /** Stops playback and clears the queued media, leaving the player reusable. */
    fun stop()
}

/** The player callbacks the ViewModel reacts to. */
sealed interface PlayerEvent {
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
