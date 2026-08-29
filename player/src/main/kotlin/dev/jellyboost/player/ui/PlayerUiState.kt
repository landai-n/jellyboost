package dev.jellyboost.player.ui

import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.model.SubtitleBackground
import dev.jellyboost.core.common.model.SubtitleTextSize
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.TrickplayTiles
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.upnext.UpNextEpisode

/**
 * Everything the player screen draws **except** the position, which ticks twice a second and lives
 * in [PlaybackPosition] for exactly that reason.
 */
internal data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: UiText? = null,
    val title: String = "",
    /** Drawn in exactly one place: behind the "Casting to …" label, where the surface would be. */
    val artworkUrl: String? = null,
    val playMethod: PlayMethod? = null,
    /**
     * Suppresses the quality picker, which caps a *streaming* bitrate and has nothing to act on for
     * a local file. The track pickers are deliberately untouched — what varies for them is their
     * content, decided by connectivity rather than by this flag (see [audioTracks]).
     */
    val isLocalPlayback: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Slow state: it changes at most twice a session, and the fast position is measured against it. */
    val durationMs: Long = 0L,
    /**
     * Already narrowed to what can actually be played: for a downloaded item, the source's full list
     * online (choosing a track the file lacks streams it instead) and only what the file holds
     * offline. Re-derived whenever connectivity changes, so an open sheet follows.
     */
    val audioTracks: List<PlaybackTrack> = emptyList(),
    /** Narrowed the same way as [audioTracks]. */
    val subtitleTracks: List<PlaybackTrack> = emptyList(),
    /** Jellyfin stream index of the active audio track. */
    val selectedAudioIndex: Int? = null,
    /** Jellyfin stream index of the active subtitle track; `null` means subtitles are off. */
    val selectedSubtitleIndex: Int? = null,
    /**
     * Media3's subtitle appearance, collected rather than read once so a change in Settings redraws the
     * cue on screen. Not applied to an ASS/SSA track libass is drawing — that one carries its own.
     */
    val subtitleTextSize: SubtitleTextSize = SubtitleTextSize.DEFAULT,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.DEFAULT,
    val quality: PlaybackQuality = PlaybackQuality.AUTO,
    /** Session-scoped and never persisted. */
    val speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    /**
     * Whether the player actually decoding this has a rate at all — a receiver's answer is only
     * knowable once it has loaded something, so it is read again at every open. `false` takes the
     * speed picker off the bar, since `CastPlayerHandle.setPlaybackSpeed` would refuse the command.
     */
    val canSetSpeed: Boolean = true,
    /** `null` is the common case: trickplay is generated per-library and off by default. */
    val trickplay: TrickplayTiles? = null,
    /**
     * Only ever set for a segment the preference says to *offer*: an auto-skip has already happened
     * by the time the state is written (`SegmentSkipController`).
     */
    val skippableSegment: MediaSegment? = null,
    /** Drives the picture-in-picture window's aspect ratio. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val hasEnded: Boolean = false,
    /** One-shot; cleared through `PlayerViewModel.consumeMessage`. */
    val userMessage: PlayerMessage? = null,
    val syncPlay: PlayerSyncPlayState = PlayerSyncPlayState(),
    val cast: PlayerCastState = PlayerCastState(),
    /**
     * Slow state despite being driven by the 500 ms tick: `PlayerViewModel.applyUpNextDecision` diffs
     * before it updates, so it is written a handful of times a session rather than twice a second.
     */
    val upNext: UpNextState? = null,
) {
    val isReady: Boolean get() = !isLoading && errorMessage == null

    /**
     * In a group the *group's* state is the truth a tap reverses: an icon drawn from the local
     * player's `isPlaying` after a missed echo shows the opposite of what the next tap must ask for.
     */
    val showsPlaying: Boolean get() = if (syncPlay.inGroup) syncPlay.groupPlaying else isPlaying
}

/**
 * The split only works if it is spelled exactly like the join: `loadTitleAndArtwork` builds the
 * label, `TitleStack` takes it apart, and a stray space between them collapses the bar to one line.
 */
internal const val PLAYER_LABEL_SEPARATOR = Separators.DOT

internal data class UpNextState(
    val episode: UpNextEpisode,
)

/**
 * @property deviceName `null` when the Cast framework has not published one, which the screen draws
 *   as a generic "casting" rather than as an empty label.
 */
internal data class PlayerCastState(
    val isCasting: Boolean = false,
    val deviceName: String? = null,
)

/**
 * Derived from `SyncPlayController.state` and deliberately smaller: no group id, no playlist item
 * ids, and — see [PlayerSyncPlayPhase] — no drift anchor. What is here is what changes a control.
 */
internal data class PlayerSyncPlayState(
    /**
     * `true` while the server, not this device, decides when playback moves: the transport becomes a
     * set of requests, the speed picker disappears, and segment auto-skip stops seeking on its own.
     */
    val inGroup: Boolean = false,
    val groupName: String = "",
    val participants: List<String> = emptyList(),
    val phase: PlayerSyncPlayPhase = PlayerSyncPlayPhase.NONE,
    /** `0` before the first `PlayQueueUpdate`. */
    val queueSize: Int = 0,
    /** `true` once the group is actually on an item, rather than merely formed. */
    val hasQueue: Boolean = false,
    val isShuffled: Boolean = false,
    val repeatMode: SyncPlayRepeatMode = SyncPlayRepeatMode.None,
    /**
     * The **group's** state, not this member's — after a `SendCommand` this device never received,
     * the local `isPlaying` can say the opposite of what the group is doing.
     */
    val groupPlaying: Boolean = false,
) {
    /** Both phases mean the same thing to the user, so the overlay draws one state. */
    val isWaitingForGroup: Boolean
        get() = inGroup && (phase == PlayerSyncPlayPhase.WAITING || phase == PlayerSyncPlayPhase.BUFFERING)
}

/**
 * A flat enum rather than `SyncPlayPhase`, whose `Playing` carries the drift anchor: that is
 * replaced on every group unpause, and would recompose the whole control surface for a value
 * nothing draws.
 */
internal enum class PlayerSyncPlayPhase {
    NONE,

    /** Prepared and waiting for the server to say go. */
    WAITING,

    /** Loading the group's item; the group has been told. */
    BUFFERING,

    PLAYING,
    PAUSED,
}

/**
 * The half of the player's state that ticks, collected *inside* the scrubber and the clock rather
 * than at screen scope: that is what stops one moving number invalidating the whole control surface.
 */
internal data class PlaybackPosition(
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
) {
    /** `0f` before the duration is known. */
    fun progress(durationMs: Long): Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun bufferedProgress(durationMs: Long): Float =
        if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** An enum rather than a string, so the ViewModel stays free of resources. */
internal enum class PlayerMessage {
    SwitchedToTranscode,

    RetryingAtLowerQuality,

    RestartedForTrackChange,

    /** Distinct from [RestartedForTrackChange]: the item plays off the network from now on. */
    StreamingForTrackChange,

    /**
     * Offline, where reopening the file cannot produce a track that is not in it; also when
     * streaming for the track was tried and the server turned out to be unreachable.
     */
    TrackUnavailableOffline,

    PlaybackFailed,

    /** A re-negotiation failed to resolve, so the session went back to the terms it was playing. */
    ChangeReverted,

    /** Only shown once an automatic rejoin has been tried. */
    SyncPlayConnectionLost,

    SyncPlayRejoined,

    SyncPlayJoinFailed,

    SyncPlayGroupEnded,

    SyncPlayRemoved,

    SyncPlayLibraryAccessDenied,

    SyncPlayItemUnavailable,

    /** Its copy names the device, taken from [PlayerCastState.deviceName] where it is drawn. */
    CastTransferred,

    /** Cast and SyncPlay are mutually exclusive, so connecting a receiver leaves the group. */
    CastLeftSyncPlayGroup,

    /**
     * Distinct from [PlaybackFailed]: every rung of the decoder-fallback ladder diagnoses *this
     * device's* decoders, so none of it was retried.
     */
    CastPlaybackFailed,
}

/** Bundled so the composables stay under the parameter limit. */
internal data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSelectAudio: (Int) -> Unit,
    val onSelectSubtitle: (Int?) -> Unit,
    val onSelectQuality: (PlaybackQuality) -> Unit,
    val onSelectSpeed: (PlaybackSpeed) -> Unit,
    val onSkipSegment: () -> Unit,
    /** Plays the next episode in this same session, without leaving the screen. */
    val onPlayNext: () -> Unit,
    val onDismissUpNext: () -> Unit,
    val onBack: () -> Unit,
    /** Every panel is hosted by `PlayerScreen`, above the control bar and its auto-hide. */
    val onOpenPanel: (PlayerPanel) -> Unit = {},
    val onSetGroupShuffle: (Boolean) -> Unit = {},
    val onSetGroupRepeat: (SyncPlayRepeatMode) -> Unit = {},
    val onLeaveGroup: () -> Unit = {},
)

/**
 * Wrapping the whole bundle rather than bumping at each call site is what stops the next action
 * added to [PlayerActions] from silently not restarting the auto-hide; the `copy` is exhaustive by
 * construction and `PlayerActionsInteractionTest` pins it.
 */
internal fun PlayerActions.reportingInteraction(onInteraction: () -> Unit): PlayerActions =
    copy(
        onPlayPause = reporting(onInteraction, onPlayPause),
        onSeekTo = reporting(onInteraction, onSeekTo),
        onSeekBy = reporting(onInteraction, onSeekBy),
        onSelectAudio = reporting(onInteraction, onSelectAudio),
        onSelectSubtitle = reporting(onInteraction, onSelectSubtitle),
        onSelectQuality = reporting(onInteraction, onSelectQuality),
        onSelectSpeed = reporting(onInteraction, onSelectSpeed),
        onSkipSegment = reporting(onInteraction, onSkipSegment),
        onPlayNext = reporting(onInteraction, onPlayNext),
        onDismissUpNext = reporting(onInteraction, onDismissUpNext),
        onBack = reporting(onInteraction, onBack),
        onOpenPanel = reporting(onInteraction, onOpenPanel),
        onSetGroupShuffle = reporting(onInteraction, onSetGroupShuffle),
        onSetGroupRepeat = reporting(onInteraction, onSetGroupRepeat),
        onLeaveGroup = reporting(onInteraction, onLeaveGroup),
    )

private fun reporting(
    onInteraction: () -> Unit,
    action: () -> Unit,
): () -> Unit =
    {
        onInteraction()
        action()
    }

/** The one-argument form — the seeks, the pickers and the panel opener. */
private fun <T> reporting(
    onInteraction: () -> Unit,
    action: (T) -> Unit,
): (T) -> Unit =
    { value ->
        onInteraction()
        action(value)
    }

/**
 * The panels `PlayerScreen` hosts above the controls — **one at a time**, which one nullable field
 * makes unrepresentable rather than merely unreachable.
 *
 * All of them are hosted by the screen so they survive the control bar composing itself out four
 * seconds after it appears: held inside the bar's `AnimatedVisibility`, a picker would be disposed
 * mid-selection.
 */
internal enum class PlayerPanel {
    AUDIO,
    SUBTITLES,
    SPEED,
    QUALITY,
    DISPLAY,
    GROUP,
    QUEUE,
}
