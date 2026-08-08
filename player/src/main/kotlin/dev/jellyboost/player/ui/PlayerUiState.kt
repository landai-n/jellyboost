package dev.jellyboost.player.ui

import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.TrickplayTiles
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode

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
internal data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: UiText? = null,
    val title: String = "",
    /**
     * The item's artwork, or `null` while it is still being fetched — or when the server has none.
     *
     * Drawn in exactly one place: behind the "Casting to …" label, where the video surface would be
     * (M12 Phase 4). A film playing on this device covers every pixel of it, so it is fetched with
     * the title, off the same `getItem`, and never on the path to the first frame.
     */
    val artworkUrl: String? = null,
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
     * Whether the player that is actually decoding this has a playback rate at all.
     *
     * Always `true` on this device, and while casting it is whatever the receiver reports through
     * `PlayerHandle.supportsPlaybackSpeed` — read again at every open, because the answer belongs to
     * the receiver rather than to the app and is only knowable once one has loaded something. `false`
     * takes the speed picker off the bar: `CastPlayerHandle.setPlaybackSpeed` refuses to send a rate
     * a receiver has not published a command for, and a picker whose every row does nothing is worse
     * than one fewer control (docs/notes/chromecast-m12-plan.md, decision 7).
     */
    val canSetSpeed: Boolean = true,
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
    /** The receiver this session is playing on, or its default "playing here" value (M12). */
    val cast: PlayerCastState = PlayerCastState(),
) {
    /** `true` once there is something on screen to control. */
    val isReady: Boolean get() = !isLoading && errorMessage == null

    /**
     * What the play/pause icon draws.
     *
     * Solo, this is just [isPlaying]. In a group it is [PlayerSyncPlayState.groupPlaying] instead —
     * the group's own state is the truth a tap reverses (`PlayerSyncPlayBridge.requestPlayPause`),
     * and an icon still drawn from the local player's `isPlaying` after a missed echo would show the
     * opposite of what the next tap actually needs to ask for, inviting the very second tap the M11
     * bug report described.
     */
    val showsPlaying: Boolean get() = if (syncPlay.inGroup) syncPlay.groupPlaying else isPlaying
}

/**
 * What joins the item's title and its episode line into [PlayerUiState.title], and what splits them
 * apart again for the top bar's two-line lockup (`String.asTitleAndSubtitle`).
 *
 * One constant rather than two literals because the split only works if it is spelled exactly like
 * the join: `PlayerViewModel.loadTitleAndArtwork` builds the label, `TitleStack` takes it apart, and
 * a stray space between them would silently collapse the bar back to one line. Points at the shared
 * [Separators.DOT] (DUP-12) rather than its own literal, but stays a named constant here because
 * both the join and the split sites need one symbol to import, not a value they could drift apart
 * by retyping.
 */
internal const val PLAYER_LABEL_SEPARATOR = Separators.DOT

/**
 * The receiver, as much of it as the player screen draws (M12 Phase 3).
 *
 * Derived from `CastStatusHolder.connection` by [PlayerCastBridge] and deliberately not the same
 * type: `CastConnection` is a sealed hierarchy the *playback* side matches on, and a control surface
 * that has to unwrap a sealed class to decide whether to draw a poster would be answering the same
 * question twice.
 *
 * @property isCasting `true` while a television has the film. It is the flag Phase 4's screen hangs
 *   everything off: the video surface becomes a poster, the gesture layer goes away (brightness and
 *   volume belong to a device that is not here) and picture-in-picture is suppressed.
 * @property deviceName the receiver's friendly name, or `null` when the Cast framework has not
 *   published one — which the screen draws as a generic "casting" rather than as an empty label.
 */
internal data class PlayerCastState(
    val isCasting: Boolean = false,
    val deviceName: String? = null,
)

/**
 * The group, as much of it as the player screen draws (M11 Phase 3).
 *
 * Derived from `SyncPlayController.state` by [PlayerSyncPlayBridge] and deliberately smaller than
 * it: no group id, no playlist item ids, and — see [PlayerSyncPlayPhase] — no drift anchor. What is
 * here is what changes a control on screen.
 */
internal data class PlayerSyncPlayState(
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
    /**
     * `true` while the **group** — not this member — is playing, `SyncPlayGroupState.Playing`.
     *
     * This, not this player's own `isPlaying`, is what a play/pause tap reverses
     * (`PlayerSyncPlayBridge.requestPlayPause`) and what [PlayerUiState.showsPlaying] draws in a
     * group: after a `SendCommand` this device never received, the local `isPlaying` can say the
     * opposite of what the group is doing, and a decision built on it either re-sends the command
     * that was already missed or shows an icon promising the wrong thing.
     */
    val groupPlaying: Boolean = false,
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
internal enum class PlayerSyncPlayPhase {
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
internal data class PlaybackPosition(
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
internal enum class PlayerMessage {
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
     * A re-negotiation failed to resolve, so the session went back to the terms it was playing.
     *
     * Raised by the recovery in `PlayerViewModel.onResolveFailed` (audit PC-01): the tapped
     * subtitle, track or quality never arrived, but the film did not end over it — it restarted
     * under the previous terms from where it had got to, and this is what tells the user their
     * change was lost rather than applied.
     */
    ChangeReverted,

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

    /**
     * The film moved to a receiver, and is playing there from where it had got to here.
     *
     * The only message whose copy names the device, which it takes from
     * [PlayerCastState.deviceName] at the moment it is drawn rather than from the message itself:
     * this enum has stayed a plain one since M5 precisely so the ViewModel never handles copy, and
     * the name is already on screen-bound state.
     */
    CastTransferred,

    /**
     * A receiver connected while this session was in a SyncPlay group, so the group was left.
     *
     * The two are mutually exclusive (docs/notes/chromecast-m12-plan.md, decision 6): SyncPlay
     * synchronises *this* player, and a player whose bytes are being decoded in a television three
     * metres away cannot be held to a group's millisecond.
     */
    CastLeftSyncPlayGroup,

    /**
     * The receiver could not play it, and nothing was retried.
     *
     * Distinct from [PlaybackFailed] because the diagnosis is: every rung of the decoder-fallback
     * ladder is a statement about *this device's* decoders, and none of them is true of a failure
     * at the far end of a network (decision 8).
     */
    CastPlaybackFailed,
}

/** Everything the controls can do, bundled so the composables stay under the parameter limit. */
internal data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSelectAudio: (Int) -> Unit,
    val onSelectSubtitle: (Int?) -> Unit,
    val onSelectQuality: (PlaybackQuality) -> Unit,
    val onSelectSpeed: (PlaybackSpeed) -> Unit,
    val onSkipSegment: () -> Unit,
    val onBack: () -> Unit,
    /**
     * Opens one of the player's seven panels — every one of them hosted by `PlayerScreen`, above the
     * control bar and above its auto-hide (audit UI-1). See [PlayerPanel].
     */
    val onOpenPanel: (PlayerPanel) -> Unit = {},
    val onSetGroupShuffle: (Boolean) -> Unit = {},
    val onSetGroupRepeat: (SyncPlayRepeatMode) -> Unit = {},
    val onLeaveGroup: () -> Unit = {},
)

/**
 * The same actions, each of which also says "the user is still here" before it acts.
 *
 * The auto-hide timer restarts on every interaction (audit UI-3), and *this* is what makes that
 * true for the whole surface at once: every way a user can act on the player — the transport
 * buttons, the gesture layer's double-tap seeks, the keyboard runner, a chip tap, a choice made in
 * a picker — goes through exactly one of these lambdas. Wrapping the bundle rather than bumping at
 * each call site is what stops the next action added to [PlayerActions] from silently not counting:
 * `copy` here is exhaustive by construction, and `PlayerActionsInteractionTest` pins that.
 *
 * [onBack] is wrapped too, though nothing is left to hide by then; a rule with no exceptions is
 * cheaper to keep true than one with a defensible one.
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
        onBack = reporting(onInteraction, onBack),
        onOpenPanel = reporting(onInteraction, onOpenPanel),
        onSetGroupShuffle = reporting(onInteraction, onSetGroupShuffle),
        onSetGroupRepeat = reporting(onInteraction, onSetGroupRepeat),
        onLeaveGroup = reporting(onInteraction, onLeaveGroup),
    )

/** [action], preceded by the interaction report. */
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
 * The seven panels `PlayerScreen` hosts above the controls — **one at a time** (audit CPX-9, UI-1).
 *
 * One nullable field rather than the independent booleans this used to be: booleans are 2^n states
 * of which only n + 1 are legal, and the illegal ones — a display sheet and a group sheet up
 * together — were unreachable only by the accident that an open panel covers the very chips that
 * open the next one. Making them unrepresentable is cheaper than relying on that.
 *
 * ### Why all seven, and not the three this started as
 * DISPLAY/GROUP/QUEUE were hoisted to the screen so they would survive the control bar composing
 * itself out four seconds after it appears — for the display sheet that is the whole reason it
 * exists, as the accessible alternative to the brightness and volume swipes (accessibility audit
 * 2026-08-05, CR-8). The other four pickers were left in the bar, held in a `remember` *inside* the
 * `AnimatedVisibility(controlsVisible)` that the auto-hide drives, so the Audio/Subtitles/Speed/
 * Quality dialog was disposed mid-selection — within a second or two of the tap, since the timer had
 * been running since before it (audit UI-1). The reasoning was right and had stopped at three of
 * seven; the split it justified is gone, and one panel enum now describes the whole set.
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
