package dev.jellyfinnative.player.ui

import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.TrickplayTiles
import dev.jellyfinnative.player.segments.MediaSegment

/**
 * Everything the player screen draws.
 *
 * [playMethod] is on screen deliberately: "is this direct playing or transcoding" is the single
 * most useful thing to know when playback misbehaves, and the M5 definition of done is checked
 * against exactly that value in the server dashboard.
 *
 * [isLocalPlayback] is the *only* thing on this screen that differs between a streamed item and a
 * downloaded one, and it hides exactly one control — see its own documentation.
 */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val title: String = "",
    val playMethod: PlayMethod? = null,
    /**
     * `true` while the bytes come off this device rather than off the server (M8).
     *
     * It suppresses the quality picker, which caps a *streaming* bitrate and therefore has nothing
     * to act on for a local file — offering it would be a control that visibly does nothing. Track
     * and subtitle pickers are deliberately untouched: those work identically either way, which is
     * the plan's "player UI byte-identical online/offline".
     */
    val isLocalPlayback: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val audioTracks: List<PlaybackTrack> = emptyList(),
    val subtitleTracks: List<PlaybackTrack> = emptyList(),
    /** Jellyfin stream index of the active audio track. */
    val selectedAudioIndex: Int? = null,
    /** Jellyfin stream index of the active subtitle track; `null` means subtitles are off. */
    val selectedSubtitleIndex: Int? = null,
    val quality: PlaybackQuality = PlaybackQuality.AUTO,
    /** Playback rate; session-scoped and never persisted (M9). */
    val speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    /**
     * Scrubbing thumbnails, or `null` when this item has none (M9).
     *
     * `null` is the common case — trickplay is generated per-library and off by default — so the
     * scrubber has to treat its absence as ordinary rather than as a failure to report.
     */
    val trickplay: TrickplayTiles? = null,
    /**
     * The intro or outro playback is currently inside, or `null`.
     *
     * Only ever set for a segment the user's preference says to *offer*: an auto-skip has already
     * happened by the time the state is written, and a segment whose preference is off never
     * reaches here (`SegmentSkipController`).
     */
    val skippableSegment: MediaSegment? = null,
    /** Decoded video size; drives the picture-in-picture window's aspect ratio (M9). */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val hasEnded: Boolean = false,
    /** One-shot message for the snackbar; cleared through `PlayerViewModel.consumeMessage`. */
    val userMessage: PlayerMessage? = null,
) {
    /** Playback progress in `0f..1f`, or `0f` before the duration is known. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Buffered fraction, drawn as the dimmer part of the seek bar. */
    val bufferedProgress: Float
        get() = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** `true` once there is something on screen to control. */
    val isReady: Boolean get() = !isLoading && errorMessage == null
}

/**
 * The one-shot messages the player raises.
 *
 * An enum rather than a string, matching `:feature:detail`, so the ViewModel stays free of
 * resources and the copy lives in `strings.xml`.
 */
enum class PlayerMessage {
    /** A decoder failed and the server was asked to transcode instead. */
    SwitchedToTranscode,

    /** The stream stalled and playback restarted at a lower bitrate. */
    RetryingAtLowerQuality,

    /** A track had to be re-requested from the server, so playback restarted. */
    RestartedForTrackChange,

    /**
     * The downloaded file does not contain that track, and offline there is no server to ask.
     *
     * The alternative — reopening the file — cannot produce a track that is not in it, so it would
     * only restart playback for nothing (`PlayerViewModel.refuseLocalTrackChange`).
     */
    TrackUnavailableOffline,

    /** Nothing left to fall back to. */
    PlaybackFailed,
}

/** Everything the controls can do, bundled so the composables stay under the parameter limit. */
data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSelectAudio: (Int) -> Unit,
    val onSelectSubtitle: (Int?) -> Unit,
    val onSelectQuality: (PlaybackQuality) -> Unit,
    val onSelectSpeed: (PlaybackSpeed) -> Unit,
    val onSkipSegment: () -> Unit,
    val onBack: () -> Unit,
)

/** The four pickers the player offers. */
internal enum class PlayerSheet {
    AUDIO,
    SUBTITLES,
    QUALITY,
    SPEED,
}
