package dev.jellyfinnative.player.ui

import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackTrack

/**
 * Everything the player screen draws.
 *
 * [playMethod] is on screen deliberately: "is this direct playing or transcoding" is the single
 * most useful thing to know when playback misbehaves, and the M5 definition of done is checked
 * against exactly that value in the server dashboard.
 */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val title: String = "",
    val playMethod: PlayMethod? = null,
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
    val onBack: () -> Unit,
)

/** The three pickers the player offers. */
internal enum class PlayerSheet {
    AUDIO,
    SUBTITLES,
    QUALITY,
}
