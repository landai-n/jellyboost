package dev.jellyboost.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.text.resolve
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.R
import dev.jellyboost.player.syncplay.ui.SyncPlayGroupSheet
import dev.jellyboost.player.syncplay.ui.SyncPlayQueueSheet
import kotlinx.coroutines.delay

/**
 * The full-screen video player.
 *
 * Everything except the video surface itself is Compose; the surface is a Media3 [PlayerView] with
 * its own controls switched off, because rendering video still requires a real `SurfaceView` and
 * reimplementing one buys nothing.
 *
 * The screen takes over the window while it is on top — landscape, system bars hidden, screen kept
 * awake — and restores all three on the way out, so leaving it mid-film cannot strand the app in
 * a rotated, chrome-less state.
 *
 * ### What M9 added to the layering
 * The order of the children in the root `Box` is load-bearing. The gesture surface sits *under* the
 * controls, so a tap on a button is consumed by the button and everything else falls through to
 * gestures. The skip-segment button sits *over* both and is deliberately not tied to the controls'
 * visibility: an intro arrives while the controls are hidden, which is exactly when the offer is
 * worth something. In picture-in-picture none of it is drawn — the window is a few hundred pixels
 * wide and the transport controls that belong there are the media notification's.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.videoPlayer.collectAsStateWithLifecycle()
    val pipState by viewModel.pipState.collectAsStateWithLifecycle()
    // Hoisted above the controls, and above the auto-hide, because a panel must survive the controls
    // getting out of the way while the user is reading the participant list (M11) — and, for the
    // display sheet, because it is the accessible alternative to the brightness and volume swipes
    // (audit CR-8), so of all three it is the one that must not disappear while it is being used.
    //
    // One field rather than three booleans (audit CPX-9): see [PlayerPanel].
    var openPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    // Rebuilt per composition, these method references are that many new unstable lambdas, and every
    // control below skips nothing (audit PERF-04/PERF-05). The ViewModel outlives the composition,
    // so one bundle is all that is ever needed.
    val actions =
        remember(viewModel, onBack) {
            PlayerActions(
                onPlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onSeekBy = viewModel::seekBy,
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
                onSelectQuality = viewModel::selectQuality,
                onSelectSpeed = viewModel::selectSpeed,
                onSkipSegment = viewModel::skipCurrentSegment,
                onBack = onBack,
                onOpenDisplaySheet = { openPanel = PlayerPanel.DISPLAY },
                onOpenGroupSheet = { openPanel = PlayerPanel.GROUP },
                onOpenQueueSheet = { openPanel = PlayerPanel.QUEUE },
                onSetGroupShuffle = viewModel::setGroupShuffle,
                onSetGroupRepeat = viewModel::setGroupRepeat,
                onLeaveGroup = viewModel::leaveGroup,
            )
        }
    // The shared one-shot idiom, keyed on the `PlayerMessage` rather than on its copy (audit
    // DUP-3/HYG-8) — which matters most here, where the three cast messages resolve through the
    // same device name and a `ChangeReverted` chasing a `RestartedForTrackChange` is routine.
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { message -> message.snackbarText(state.cast) }
    var controlsVisible by remember { mutableStateOf(true) }
    val inPictureInPicture = pipState.isInPictureInPicture

    ImmersiveLandscapeEffect(enabled = !inPictureInPicture)

    // The position poll that drives the seek bar only runs while this screen is visible — the
    // lifecycle, not the composition: pressing Home keeps the composable composed for the whole
    // film, and a 500 ms poll behind a backgrounded screen is exactly the battery burn the
    // UI/reporting split exists to avoid (audit PC-06). Picture-in-picture keeps the activity
    // started, so the floating window's position stays live. Playback and progress reporting are
    // deliberately not tied to this — that is what lets the app be backgrounded without the film
    // stopping (M9).
    LifecycleStartEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onStopOrDispose { viewModel.setScreenVisible(false) }
    }

    ControlsAutoHideEffect(
        visible = controlsVisible,
        isPlaying = state.isPlaying,
        onHide = { controlsVisible = false },
    )

    // Keyboard operation of the player (audit CR-4). The root takes focus on entry so the shortcuts
    // work before anything has been tabbed to, and gives it up the moment the user tabs into the
    // control bar — see [PlayerKeyScope] for which key wins where.
    val focusRequester = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }

    val runKeyCommand = remember(actions) { playerKeyRunner(actions) { controlsVisible = true } }

    LaunchedEffect(state.hasEnded) {
        if (state.hasEnded) onBack()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .onFocusChanged { rootFocused = it.isFocused }
                // `focusTarget`, not `focusable`: this node exists to receive key events, and
                // `focusable` would additionally publish a screen-sized semantics node for TalkBack
                // to stop on — an empty stop over the very surface CR-1 just labelled.
                .focusTarget()
                .onPreviewKeyEvent { event -> handlePlayerKey(event, rootFocused, preview = true, runKeyCommand) }
                .onKeyEvent { event -> handlePlayerKey(event, rootFocused, preview = false, runKeyCommand) },
    ) {
        // The one branch that decides what this screen *is*: a video player, or the remote control
        // for one three metres away (docs/notes/chromecast-m12-plan.md, decision 10).
        if (state.cast.isCasting) {
            CastingBackdrop(state = state, modifier = Modifier.fillMaxSize())
        } else {
            VideoSurface(player = player, modifier = Modifier.fillMaxSize())
        }

        // Bare video in the floating window; the notification carries the transport controls. A
        // positive `if` around the skipped children rather than the `return@Box` this used to be
        // (audit CPX-9): an early return in a layout scope silently swallows every sibling appended
        // after it, so the next thing added to this screen would simply never be drawn in
        // picture-in-picture — and nothing would say so.
        if (!inPictureInPicture) {
            PlayerGestureLayer(
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekBy = actions.onSeekBy,
                // Both vertical swipes act on this device: one moves its media volume, the other its
                // backlight. While a television has the film the first is inaudible and the second
                // dims a still image — the receiver's volume is the hardware keys' job, which the
                // Cast framework routes for as long as the session lasts. Taps and double-tap seeks
                // stay: they are the controls' own, and a screen that could not bring them back
                // would be a remote control with no buttons.
                swipesEnabled = !state.cast.isCasting,
            )

            when {
                state.errorMessage != null ->
                    ErrorState(
                        message = requireNotNull(state.errorMessage).resolve(),
                        // Assertive: the film has stopped and the only thing left on the screen is
                        // this panel, so it is worth interrupting whatever is being read (audit
                        // CR-3).
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .semantics { liveRegion = LiveRegionMode.Assertive },
                        onRetry = onBack,
                        // Named for what it does, not for what an error screen's button usually
                        // does: there is nothing to retry here — the session is gone and the only
                        // way out is back to where the user came from (WCAG 2.5.3, accessibility
                        // audit 2026-08-05). `player_back` is the same three words, already
                        // translated everywhere, and describes the same action the top-left button
                        // performs.
                        actionLabel = stringResource(R.string.player_back),
                    )

                state.isLoading -> LoadingState(modifier = Modifier.align(Alignment.Center))

                else ->
                    AnimatedVisibility(
                        visible = controlsVisible,
                        modifier = Modifier.fillMaxSize(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        PlayerControls(state = state, position = viewModel.position, actions = actions)
                    }
            }

            // Not while the session is still opening: that already draws a spinner, and two of them
            // centred on top of each other say less than one.
            if (state.syncPlay.isWaitingForGroup && state.isReady) {
                WaitingForGroupOverlay(
                    syncPlay = state.syncPlay,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            BufferingIndicator(state = state, modifier = Modifier.align(Alignment.Center))

            state.skippableSegment?.let { segment ->
                SkipSegmentButton(
                    kind = segment.kind,
                    onClick = actions.onSkipSegment,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = Dimens.SpaceExtraLarge, bottom = SKIP_BUTTON_BOTTOM_PADDING)
                            // The offer is time-boxed — it is gone once the segment is — so a user
                            // who is not looking at the screen has to be *told* it exists, not left
                            // to find it by traversal (audit CR-3). Polite: it is an offer, not an
                            // emergency.
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // The player consumes no window insets of its own — it is immersive and full-bleed — so
            // the shared host's chrome/gesture-bar rule resolves to zero here and [SNACKBAR_PADDING]
            // is what actually applies: the floor exists for exactly this screen (audit DUP-3).
            JellyboostSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
                minimumBottomInset = SNACKBAR_PADDING,
            )
        }
    }

    // Outside the video `Box`, so it is not covered by the controls and not drawn in the floating
    // window; never in picture-in-picture, where it would be wider than the window itself.
    if (!inPictureInPicture) {
        PanelHost(
            panel = openPanel,
            syncPlay = state.syncPlay,
            actions = actions,
            onDismiss = { openPanel = null },
        )
    }
}

/**
 * Draws whichever of the screen's three panels is open, or nothing.
 *
 * One `when` over [PlayerPanel] rather than three independently-gated hosts: the exhaustive branch is
 * what makes "one panel at a time" a property of the code instead of a property of the call sites
 * (audit CPX-9). The membership gates the group and queue panels carry are unchanged and stay
 * *inside* their branches, because they answer a different question — not "did the user tap this"
 * but "is the thing this panel is about still there".
 */
@Composable
private fun PanelHost(
    panel: PlayerPanel?,
    syncPlay: PlayerSyncPlayState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
) {
    when (panel) {
        null -> Unit

        PlayerPanel.DISPLAY -> PlayerDisplayDialog(onDismiss = onDismiss)

        // Gated on membership as well as on the tap: a group that ends while the sheet is open takes
        // the sheet with it, rather than leaving a panel about a group that no longer exists.
        PlayerPanel.GROUP ->
            if (syncPlay.inGroup) {
                SyncPlayGroupSheet(
                    state = syncPlay,
                    onSetShuffle = actions.onSetGroupShuffle,
                    onSetRepeat = actions.onSetGroupRepeat,
                    onLeave = {
                        actions.onLeaveGroup()
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            }

        // Gated on membership like the group sheet, for the same reason — a group that ends takes its
        // queue with it. The sheet's own ViewModel reads the controller's queue, and a group that has
        // not been given anything to watch yet has none (M11 Phase 4); the chip that opens this is
        // only offered once it has (`sheetChipSpecs`).
        PlayerPanel.QUEUE -> if (syncPlay.inGroup) SyncPlayQueueSheet(onDismiss = onDismiss)
    }
}

/**
 * "Nothing is happening, and it is not your fault."
 *
 * Drawn while the group is gated on someone buffering — this member or another one — which is
 * otherwise indistinguishable from a stall: the video is frozen, the controls say paused, and the
 * user's next move would be to tap Play, which in a group only asks the server for something it is
 * already refusing to do.
 *
 * The panel is the refresh's *dark* glass rather than `Modifier.glassSurface`: this one floats over
 * a video frame with no Haze backdrop behind it, where a 6%-white fill would leave white text on
 * whatever the film happens to be showing. The scrim it already had stays, and takes the panel
 * radius and the glass hairline.
 */
@Composable
private fun WaitingForGroupOverlay(
    syncPlay: PlayerSyncPlayState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                // One node, announced when it appears: "nothing is happening and it is not your
                // fault" is exactly the sort of thing a user who cannot see the frozen frame needs
                // said (audit CR-3). Merged so the participant list arrives with the message rather
                // than as a second stop.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .background(color = OVERLAY_SCRIM, shape = RoundedCornerShape(Dimens.PanelRadius))
                .border(
                    width = GlassDefaults.HairlineWidth,
                    color = GlassDefaults.Hairline,
                    shape = RoundedCornerShape(Dimens.PanelRadius),
                ).padding(Dimens.SpaceExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = stringResource(R.string.player_syncplay_waiting),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        if (syncPlay.participants.isNotEmpty()) {
            Text(
                text = syncPlay.participants.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = DIM_ALPHA),
            )
        }
    }
}

/**
 * The mid-playback rebuffer spinner — and, until now, the player's biggest silence.
 *
 * [PlayerUiState.isBuffering] has been computed since M9 and had **no UI consumer at all** (audit
 * CR-3): a stream that stalled mid-film showed a frozen frame, an unchanged clock and no spinner, so
 * the app was indistinguishable from a crash whether or not the user could see it. This draws the
 * missing spinner and announces it politely.
 *
 * The gating is what keeps it honest rather than flickery:
 * - not while the session is still opening ([PlayerUiState.isReady]) — `LoadingState` is already
 *   centred there, and two spinners on top of each other say less than one;
 * - not while the group is waiting, for the same reason: [WaitingForGroupOverlay] is a better answer
 *   to the same frozen frame, and it names the reason;
 * - not while a receiver has the film, where "buffering" is a statement about a decoder three metres
 *   away that this device cannot see.
 */
@Composable
private fun BufferingIndicator(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val visible =
        state.isBuffering &&
            state.isReady &&
            !state.syncPlay.isWaitingForGroup &&
            !state.cast.isCasting
    if (!visible) return

    val label = stringResource(R.string.player_buffering)

    CircularProgressIndicator(
        color = Color.White,
        modifier =
            modifier.semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * The "Skip intro" / "Skip outro" offer.
 *
 * A glass pill since the 2026 refresh, where it had been a filled Material button: the white fill
 * now belongs to the play/pause disc alone, and a second solid surface on the screen would compete
 * with it. The pill keeps everything that made the offer findable — bottom-right, clear of the
 * scrubber, a leading glyph and a word — and the appearance rules are untouched: it is composed only
 * while `PlayerUiState.skippableSegment` holds, whatever the controls are doing.
 */
@Composable
private fun SkipSegmentButton(
    kind: MediaSegmentKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GhostPillButton(
        text =
            stringResource(
                when (kind) {
                    MediaSegmentKind.INTRO -> R.string.player_skip_intro
                    MediaSegmentKind.OUTRO -> R.string.player_skip_outro
                },
            ),
        onClick = onClick,
        modifier = modifier,
        small = true,
        leadingIcon = Icons.Filled.SkipNext,
        // Over raw video, with no controls scrim behind it — the flat dark fill the player's other
        // glass uses (see `PlayerControls`' header), not the in-content white@6%.
        tint = VIDEO_GLASS_FILL,
    )
}

/**
 * What the player draws instead of video while a television has the film (M12 Phase 4).
 *
 * There is nothing to render here — `CastPlayerHandle.player` is permanently `null` — so the
 * surface is replaced rather than left attached to nothing: the item's own artwork, dimmed, with
 * the receiver's name over it. Every control around it keeps working, because none of them ever
 * touched the surface; this screen is the remote control now (decision 10).
 *
 * Two consequences of *removing* [VideoSurface] rather than hiding it, both wanted: the phone's
 * screen may sleep again (`keepScreenOn` belonged to the `PlayerView`), and picture-in-picture has
 * nothing to float — which is why `PlayerViewModel.publishPipState` disarms it in the same breath.
 *
 * The label is offset above centre rather than centred: the transport row owns the middle of this
 * screen and the bottom bar owns the last hundred dip of it, so a caption at either would be read
 * through a 64 dp play button or through the scrubber. Measuring *from* the centre rather than from
 * the top edge is what keeps it clear of the top bar on a phone in landscape (roughly 360 dp of
 * height) and still visually attached to the artwork on a tablet.
 */
@Composable
private fun CastingBackdrop(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val device = state.cast.deviceName ?: stringResource(R.string.player_cast_device_unnamed)

    Box(modifier = modifier) {
        // Fitted, not cropped, and with no placeholder icon: the artwork may be a wide backdrop or
        // a 2:3 poster depending on what the server has for this item, and cropping the second to a
        // landscape screen shows a hand-span of somebody's chin. An item with no artwork at all
        // simply leaves the black behind the label.
        JellyfinAsyncImage(
            url = state.artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            placeholderIcon = null,
        )
        // Says "this is not playing here" at a glance, and buys the controls their contrast back.
        Box(modifier = Modifier.fillMaxSize().background(BACKDROP_SCRIM))

        Row(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(y = -CAST_LABEL_OFFSET)
                    .background(OVERLAY_SCRIM, RoundedCornerShape(Dimens.CardCornerRadius))
                    .padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Outlined.Cast,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(CAST_LABEL_ICON),
            )
            Text(
                text = stringResource(R.string.player_casting_to, device),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * Takes the controls away on their own while something is playing; a paused player keeps them,
 * because a paused film with no controls looks like a frozen app.
 *
 * Two accessibility conditions on that (audit CR-1). **While touch exploration is on the controls
 * never hide at all**: a screen-reader user reads the bar one element at a time, and four seconds is
 * not a traversal — the controls would vanish mid-swipe, every time, and until CR-1's tap action
 * there was no way to ask for them back. Suppressing beats stretching here: no finite timeout is
 * long enough for "read every control", and a bar that stays up is exactly what a user who is
 * exploring the screen wants. When touch exploration is off, the four seconds still pass through
 * [recommendedControlsTimeoutMs], so the system's "time to take action" preference is honoured.
 */
@Composable
private fun ControlsAutoHideEffect(
    visible: Boolean,
    isPlaying: Boolean,
    onHide: () -> Unit,
) {
    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    val timeoutMs = recommendedControlsTimeoutMs()
    val shouldHide = visible && isPlaying && !touchExplorationEnabled

    LaunchedEffect(shouldHide, timeoutMs) {
        if (!shouldHide) return@LaunchedEffect
        delay(timeoutMs)
        onHide()
    }
}

/**
 * How long the controls should linger, once the system's own accessibility timeout preference has
 * had its say.
 *
 * `calculateRecommendedTimeoutMillis` is how "time to take action" (Settings → Accessibility) reaches
 * an app: it returns [CONTROLS_TIMEOUT_MS] for a user who has not asked for more, and a longer value
 * for one who has — including "until dismissed", which is why the caller also has the touch
 * exploration escape hatch.
 */
@Composable
private fun recommendedControlsTimeoutMs(): Long =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = CONTROLS_TIMEOUT_MS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: CONTROLS_TIMEOUT_MS

/**
 * Whether the system is exploring by touch — TalkBack, and anything else that reads the screen under
 * a finger.
 *
 * Observed rather than read once: a user may turn TalkBack on while a film is running (that is
 * precisely when they discover the controls have vanished), and a value captured at composition
 * would leave the auto-hide behaving as if it were still off for the rest of the session.
 */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager =
        remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }

    DisposableEffect(manager) {
        val service = manager ?: return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { value -> enabled = value }
        service.addTouchExplorationStateChangeListener(listener)
        // The state can have changed between the `remember` above and this registration.
        enabled = service.isTouchExplorationEnabled
        onDispose { service.removeTouchExplorationStateChangeListener(listener) }
    }

    return enabled
}

/**
 * The video output.
 *
 * `useController = false`: the transport controls are Compose, and Media3's own would fight them
 * for touches. `keepScreenOn` is set here rather than on the window so it follows the surface's
 * lifetime exactly.
 *
 * The semantics are cleared (audit A11Y-P-21): a `PlayerView` with no controller still carries the
 * view hierarchy Media3 builds inside it, and whatever of it reaches the accessibility tree would be
 * stray nodes over the video — the surface has nothing to say, and the gesture layer above it is
 * what offers the one action there is.
 */
@Composable
@UnstableApi
private fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.clearAndSetSemantics { },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}

/**
 * Puts the window into immersive full-screen for as long as the player is composed, and hands the
 * orientation back to the user's own rotation setting.
 *
 * The previous orientation and system-bar behaviour are captured and restored on dispose; the
 * project's test device is a tablet, where getting this wrong leaves the whole app sideways.
 *
 * Suspended in picture-in-picture ([enabled] `false`): a floating window has no system bars to hide
 * and no orientation to force, and asking for an orientation while the system is resizing the window
 * fights the animation.
 *
 * ### Why `SCREEN_ORIENTATION_USER` rather than sensor-landscape
 * WCAG 1.3.4 allows a forced orientation only where it is essential, and video playback is not —
 * this player already renders at arbitrary aspect ratios in picture-in-picture (accessibility audit
 * 2026-08-05, MANIFEST-01; DECISIONS.md 2026-08-05). `SCREEN_ORIENTATION_USER` follows the device:
 * with rotation unlocked a turned tablet still plays landscape, exactly as before, but a user whose
 * system rotation is locked — including anyone in a fixed mount — is no longer overridden by the one
 * screen in the app that used to insist.
 */
@Composable
private fun ImmersiveLandscapeEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }

        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        val previousDecorFitsSystemWindows = true
        // The brightness swipe writes a per-window override (`PlayerGestureLayer`), and in a
        // single-activity app the window outlives the player — nothing undoes the override on its
        // own, so a film dimmed for the night would leave every other screen dimmed too (audit
        // PC-02). Captured with the rest of the window state; `BRIGHTNESS_OVERRIDE_NONE` is the
        // usual "no override" starting value, and restoring it hands the backlight back to the
        // system.
        val previousBrightness = window.attributes.screenBrightness

        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, previousDecorFitsSystemWindows)
            activity.requestedOrientation = previousOrientation
            window.attributes =
                WindowManager.LayoutParams().apply {
                    copyFrom(window.attributes)
                    screenBrightness = previousBrightness
                }
        }
    }
}

/**
 * The message, in the words the user reads.
 *
 * Every message is formatted with the receiver's name whether or not it mentions one: a format
 * argument a string has no placeholder for is simply dropped, and one call site is cheaper than a
 * second path for the three cast messages that do need it. [PlayerCastState.deviceName] is `null`
 * whenever the Cast framework has not published a name, which reads as a generic "your TV" rather
 * than as a gap in the sentence.
 */
@Composable
private fun PlayerMessage.snackbarText(cast: PlayerCastState): String =
    stringResource(textRes(), cast.deviceName ?: stringResource(R.string.player_cast_device_unnamed))

@Suppress("CyclomaticComplexMethod") // A one-to-one table; splitting it would only hide it.
private fun PlayerMessage.textRes(): Int =
    when (this) {
        PlayerMessage.SwitchedToTranscode -> R.string.player_message_transcode
        PlayerMessage.RetryingAtLowerQuality -> R.string.player_message_lower_quality
        PlayerMessage.RestartedForTrackChange -> R.string.player_message_track_restart
        PlayerMessage.StreamingForTrackChange -> R.string.player_message_track_streaming
        PlayerMessage.TrackUnavailableOffline -> R.string.player_message_track_offline
        PlayerMessage.PlaybackFailed -> R.string.player_message_failed
        PlayerMessage.ChangeReverted -> R.string.player_message_change_reverted
        PlayerMessage.SyncPlayConnectionLost -> R.string.player_message_syncplay_connection_lost
        PlayerMessage.SyncPlayRejoined -> R.string.player_message_syncplay_rejoined
        PlayerMessage.SyncPlayJoinFailed -> R.string.player_message_syncplay_join_failed
        PlayerMessage.SyncPlayGroupEnded -> R.string.player_message_syncplay_group_ended
        PlayerMessage.SyncPlayRemoved -> R.string.player_message_syncplay_removed
        PlayerMessage.SyncPlayLibraryAccessDenied -> R.string.player_message_syncplay_library_denied
        PlayerMessage.SyncPlayItemUnavailable -> R.string.player_message_syncplay_item_unavailable
        PlayerMessage.CastTransferred -> R.string.player_message_cast_transferred
        PlayerMessage.CastLeftSyncPlayGroup -> R.string.player_message_cast_left_syncplay
        PlayerMessage.CastPlaybackFailed -> R.string.player_message_cast_failed
    }

/** Enough contrast for white text over a bright frame, without blacking the video out. */
private val OVERLAY_SCRIM = Color.Black.copy(alpha = 0.6f)

/**
 * Dims the casting artwork so it reads as a still, not as a paused frame — and buys the controls
 * over it their contrast.
 *
 * 0.62 rather than the original 0.45 (accessibility audit 2026-08-05): the artwork here is whatever
 * the item's poster happens to be, and against a bright one black@45% composited to rgb(140), where
 * the white "Casting to …" label and the transport over it read 3.35:1 — short of 4.5:1. Black@62%
 * takes the same worst case to rgb(97) and 6.20:1, and is the same number `PlayerControls.SCRIM`
 * now uses, so the two washes the player can draw over a bright image agree.
 */
private val BACKDROP_SCRIM = Color.Black.copy(alpha = 0.62f)

/** Clears the transport row's 64 dp play button, whatever the viewport's height. */
private val CAST_LABEL_OFFSET = 88.dp

private val CAST_LABEL_ICON = 20.dp

/**
 * The participant list under the SyncPlay waiting message, held off full white.
 *
 * [OVERLAY_SCRIM] stays at 0.6 — it is the panel's fill, shares its value with
 * `PlayerControls.VIDEO_GLASS_FILL`, and full-white text on it already reads at 5.74:1 over the
 * worst case (a white video frame, composited to rgb(102)); darkening the panel instead would have
 * split that pairing for one secondary line. What was wrong is this alpha: over that same rgb(102)
 * white needs α ≥ 0.821 for 4.5:1, and 0.7 gave 3.76:1. 0.85 gives 4.69:1.
 */
private const val DIM_ALPHA = 0.85f

/** Leaves room for the controls bar so a snackbar never covers the seek bar. */
private val SNACKBAR_PADDING = 96.dp

/** Clears the seek bar, so the skip offer never sits on top of the scrubber. */
private val SKIP_BUTTON_BOTTOM_PADDING = 112.dp

/** How long the controls linger after a tap while something is playing. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * The two things this file draws *over* the controls, at the positions the screen gives them.
 *
 * Not a preview of [PlayerScreen] itself: the screen owns a `PlayerView`, a `hiltViewModel()` and a
 * window it puts into immersive landscape, none of which a preview can supply.
 */
@Preview(name = "Player overlays · phone landscape", widthDp = 800, heightDp = 360)
@Composable
private fun PlayerOverlaysPhoneLandscapePreview() {
    PlayerOverlaysPreview()
}

@Preview(name = "Player overlays · tablet landscape", widthDp = 1138, heightDp = 640)
@Composable
private fun PlayerOverlaysTabletLandscapePreview() {
    PlayerOverlaysPreview()
}

@Composable
private fun PlayerOverlaysPreview() {
    JellyfinTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            WaitingForGroupOverlay(
                syncPlay =
                    PlayerSyncPlayState(
                        inGroup = true,
                        groupName = "Film night",
                        participants = listOf("Alex", "Claude"),
                    ),
                modifier = Modifier.align(Alignment.Center),
            )
            SkipSegmentButton(
                kind = MediaSegmentKind.INTRO,
                onClick = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Dimens.SpaceExtraLarge, bottom = SKIP_BUTTON_BOTTOM_PADDING),
            )
        }
    }
}
