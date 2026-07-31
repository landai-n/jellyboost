package dev.jellyfinnative.player.ui

import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.TrickplayTiles
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode

/**
 * Everything the player screen draws **except** the position, which ticks twice a second and lives
 * in [PlaybackPosition] for exactly that reason.
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
     * the plan's "player UI byte-identical online/offline". What varies is their *content*, and
     * that is decided by connectivity rather than by this flag — see [audioTracks].
     */
    val isLocalPlayback: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /**
     * The item's length.
     *
     * Stays here rather than moving to [PlaybackPosition] with the position: it changes at most
     * twice per session — once from the server's runtime, once when the container disagrees and the
     * player wins — so it costs nothing where the slow state is, and it is what the clock and the
     * scrubber measure the fast state *against*.
     */
    val durationMs: Long = 0L,
    /**
     * What the audio picker offers — already narrowed to what can actually be played.
     *
     * For a streamed item that is the source's track list. For a **downloaded** one it depends on
     * the connection: online it is still the source's full list, and choosing a track the file does
     * not hold streams the item instead; offline it is only what the file and its sidecars can
     * supply, because a row that cannot do anything is worse than one fewer language. The ViewModel
     * re-derives both lists whenever connectivity changes, so a sheet that is already open follows.
     */
    val audioTracks: List<PlaybackTrack> = emptyList(),
    /** What the subtitle picker offers, narrowed the same way as [audioTracks]. */
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
    /** The group this session is watching with, or its default "no group" value (M11). */
    val syncPlay: PlayerSyncPlayState = PlayerSyncPlayState(),
) {
    /** `true` once there is something on screen to control. */
    val isReady: Boolean get() = !isLoading && errorMessage == null
}

/**
 * The group, as much of it as the player screen draws (M11 Phase 3).
 *
 * Derived from `SyncPlayController.state` by [PlayerSyncPlayBridge] and deliberately smaller than
 * it: no group id, no playlist item ids, and — see [PlayerSyncPlayPhase] — no drift anchor. What is
 * here is what changes a control on screen.
 */
data class PlayerSyncPlayState(
    /**
     * `true` while the server, not this device, decides when playback moves.
     *
     * It is the flag every in-group behaviour hangs off: the transport becomes a set of requests,
     * the speed picker disappears (there is no per-member playback rate in SyncPlay), and segment
     * auto-skip stops seeking on its own.
     */
    val inGroup: Boolean = false,
    val groupName: String = "",
    /** Display names of everyone in the group, this user included. */
    val participants: List<String> = emptyList(),
    val phase: PlayerSyncPlayPhase = PlayerSyncPlayPhase.NONE,
    /** How many items the group has queued; `0` before the first `PlayQueueUpdate`. */
    val queueSize: Int = 0,
    /** `true` once the group is actually on an item, rather than merely formed. */
    val hasQueue: Boolean = false,
    val isShuffled: Boolean = false,
    val repeatMode: SyncPlayRepeatMode = SyncPlayRepeatMode.None,
) {
    /**
     * `true` while the group is not playing because someone — possibly this member — is not ready.
     *
     * Both phases mean the same thing to the user ("nothing is happening and it is not your fault"),
     * and the difference between them is which end is loading, so the overlay draws one state.
     */
    val isWaitingForGroup: Boolean
        get() = inGroup && (phase == PlayerSyncPlayPhase.WAITING || phase == PlayerSyncPlayPhase.BUFFERING)
}

/**
 * What this member is doing inside the group, with the protocol detail taken out.
 *
 * A flat enum rather than `SyncPlayPhase` because that type's `Playing` carries the drift anchor,
 * which is replaced on every group unpause — putting it in [PlayerUiState] would make the whole
 * control surface recompose for a value nothing draws (audit PERF-04).
 */
enum class PlayerSyncPlayPhase {
    /** Not in a group at all. */
    NONE,

    /** Prepared and waiting for the server to say go. */
    WAITING,

    /** Loading the group's item; the group has been told. */
    BUFFERING,

    PLAYING,
    PAUSED,
}

/**
 * The half of the player's state that ticks (audit PERF-04).
 *
 * Published as its own `StateFlow` and read *inside* the scrubber and the clock rather than at
 * screen scope. Position changes twice a second while the controls are up; every other field on
 * [PlayerUiState] changes at most a handful of times a session, and a `StateFlow` conflates values
 * that compare equal — so keeping the two apart is what stops one moving number from invalidating
 * the whole control surface.
 *
 * The duration lives on [PlayerUiState] and is passed in, because a fraction of an unknown length is
 * not a number this class can produce.
 */
data class PlaybackPosition(
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
) {
    /** Playback progress in `0f..1f`, or `0f` before the duration is known. */
    fun progress(durationMs: Long): Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Buffered fraction, drawn as the dimmer part of the seek bar. */
    fun bufferedProgress(durationMs: Long): Float =
        if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
     * A downloaded item left its file to stream, because the track the user picked is not in it.
     *
     * Distinct from [RestartedForTrackChange] because the consequence is different and worth saying:
     * the item plays off the network from now on (`PlayerViewModel.selectAudioTrack`).
     */
    StreamingForTrackChange,

    /**
     * The downloaded file does not contain that track, and there is no server to ask.
     *
     * Raised when the app is offline — where the alternative, reopening the file, cannot produce a
     * track that is not in it and would only restart playback for nothing
     * (`PlayerViewModel.refuseLocalTrackChange`) — and when streaming for the track was tried and
     * the server turned out to be unreachable after all.
     */
    TrackUnavailableOffline,

    /** Nothing left to fall back to. */
    PlaybackFailed,

    /**
     * The connection dropped while in a group, so the group was left and playback paused.
     *
     * Key decision 10 as amended (docs/notes/syncplay-m11-plan.md): resuming from here plays solo —
     * from the downloaded file if there is one. Only shown once an automatic rejoin has been tried.
     */
    SyncPlayConnectionLost,

    /** The server had dropped this session from the group, and it was taken back automatically. */
    SyncPlayRejoined,

    /** The group could not be joined; nothing changed. */
    SyncPlayJoinFailed,

    /** The group ended — the last other member left, or the server restarted. */
    SyncPlayGroupEnded,

    /** The server says this session is no longer in the group. */
    SyncPlayRemoved,

    /** The group moved to something this account may not see. */
    SyncPlayLibraryAccessDenied,

    /** The group's current item could not be opened on this device. */
    SyncPlayItemUnavailable,
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
    /** Opens the group sheet; only ever reachable while [PlayerSyncPlayState.inGroup] (M11). */
    val onOpenGroupSheet: () -> Unit = {},
    /** Opens the group queue sheet; only reachable once the group actually has a queue (M11). */
    val onOpenQueueSheet: () -> Unit = {},
    val onSetGroupShuffle: (Boolean) -> Unit = {},
    val onSetGroupRepeat: (SyncPlayRepeatMode) -> Unit = {},
    val onLeaveGroup: () -> Unit = {},
)

/** The four pickers the player offers. */
internal enum class PlayerSheet {
    AUDIO,
    SUBTITLES,
    QUALITY,
    SPEED,
}
