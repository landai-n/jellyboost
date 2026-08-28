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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onPlaced
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
import androidx.hilt.navigation.compose.hiltViewModel
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
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The full-screen video player.
 *
 * **The order of the children in the root `Box` is load-bearing.** The gesture surface sits *under*
 * the controls, so a tap on a button is consumed by the button and everything else falls through to
 * gestures; the bottom-right offers sit *over* both and are deliberately not tied to the controls'
 * visibility, since an intro or an ending arrives while the chrome is hidden.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerScreen(viewModel = hiltViewModel(), onBack = onBack, modifier = modifier)
}

/** Separate from the public overload so tests can supply a ViewModel. */
@Suppress(
    "LongMethod",
)
@Composable
internal fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.videoPlayer.collectAsStateWithLifecycle()
    val assSubtitleHandler by viewModel.assSubtitleHandler.collectAsStateWithLifecycle()
    val pipState by viewModel.pipState.collectAsStateWithLifecycle()
    // Hoisted above the controls and above the auto-hide: a panel held inside the control bar would
    // be disposed mid-selection by the bar's own `AnimatedVisibility`.
    var openPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    // Counted, not time-stamped: it is only ever compared with itself as a `LaunchedEffect` key.
    var interactions by remember { mutableIntStateOf(0) }
    // Remembered once: rebuilt per composition these method references are new unstable lambdas and
    // every control below skips nothing.
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
                onPlayNext = viewModel::playNextEpisode,
                onDismissUpNext = viewModel::dismissUpNext,
                onBack = onBack,
                onOpenPanel = { panel -> openPanel = panel },
                onSetGroupShuffle = viewModel::setGroupShuffle,
                onSetGroupRepeat = viewModel::setGroupRepeat,
                onLeaveGroup = viewModel::leaveGroup,
                // One wrapper for the whole bundle, so an action added later cannot quietly stop
                // counting as use of the player.
            ).reportingInteraction { interactions++ }
        }
    // Keyed on the `PlayerMessage`, not on its copy: the three cast messages resolve through the
    // same device name.
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { message -> message.snackbarText(state.cast) }
    var controlsVisible by remember { mutableStateOf(true) }
    val inPictureInPicture = pipState.isInPictureInPicture

    ImmersiveLandscapeEffect(enabled = !inPictureInPicture)

    // The lifecycle, not the composition: pressing Home keeps this composed for the whole film, and
    // the 500 ms poll behind a backgrounded screen is pure battery burn. Playback and progress
    // reporting are deliberately not tied to this.
    LifecycleStartEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onStopOrDispose { viewModel.setScreenVisible(false) }
    }

    ControlsAutoHideEffect(
        visible = controlsVisible,
        isPlaying = state.isPlaying,
        panelOpen = openPanel != null,
        interactions = interactions,
        onHide = { controlsVisible = false },
    )

    // The root takes focus on entry so shortcuts work before anything has been tabbed to, and gives
    // it up when the user tabs into the control bar — see [PlayerKeyScope].
    val focusRequester = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    val focusClaimed = remember { mutableStateOf(false) }

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
                // Claimed at first placement, not from a `LaunchedEffect`: a `FocusRequester` throws
                // until its node is placed, and an effect can win that race and leave the keyboard
                // shortcuts silently dead. The latch is read nowhere in composition, so writing it
                // during layout invalidates nothing.
                .onPlaced {
                    if (!focusClaimed.value) {
                        focusClaimed.value =
                            runCatching { focusRequester.requestFocus() }
                                .onFailure { error -> Timber.w(error, "The player root could not take focus") }
                                .isSuccess
                    }
                }
                // `focusTarget`, not `focusable`: this node exists to receive key events, and
                // `focusable` would additionally publish a screen-sized semantics node for TalkBack
                // to stop on — an empty stop over the very surface the gesture layer labels.
                .focusTarget()
                .onPreviewKeyEvent { event -> handlePlayerKey(event, rootFocused, preview = true, runKeyCommand) }
                .onKeyEvent { event -> handlePlayerKey(event, rootFocused, preview = false, runKeyCommand) },
    ) {
        if (state.cast.isCasting) {
            CastingBackdrop(state = state, modifier = Modifier.fillMaxSize())
        } else {
            VideoSurface(
                player = player,
                assSubtitleHandler = assSubtitleHandler,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // A positive `if`, never an early `return@Box`: a return in a layout scope silently swallows
        // every sibling appended after it.
        if (!inPictureInPicture) {
            PlayerGestureLayer(
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekBy = actions.onSeekBy,
                // Both vertical swipes act on *this* device, so while a receiver has the film they
                // are meaningless; the receiver's volume is the hardware keys' job, routed by the
                // Cast framework. Taps and double-tap seeks stay.
                swipesEnabled = !state.cast.isCasting,
            )

            when {
                state.errorMessage != null ->
                    ErrorState(
                        message = requireNotNull(state.errorMessage).resolve(),
                        // Assertive: the film has stopped and this panel is all that is left.
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .semantics { liveRegion = LiveRegionMode.Assertive },
                        onRetry = onBack,
                        // Named for what it does: there is nothing to retry — the session is gone
                        // and the only way out is back (WCAG 2.5.3).
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

            // Not while the session is still opening: `LoadingState` already draws a spinner there.
            if (state.syncPlay.isWaitingForGroup && state.isReady) {
                WaitingForGroupOverlay(
                    syncPlay = state.syncPlay,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Only while the chrome is hidden: with the controls up, the transport row's own
            // buffering disc already says it, in the same place.
            if (!controlsVisible) {
                BufferingIndicator(state = state, modifier = Modifier.align(Alignment.Center))
            }

            // Stacked, not aligned independently: an intro offer can co-occur with the up-next card
            // (the outro one is suppressed by `PlayerViewModel.applySegmentDecision`), and two
            // overlays sharing a corner must not draw on top of each other.
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Dimens.SpaceExtraLarge, bottom = SKIP_BUTTON_BOTTOM_PADDING),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            ) {
                state.upNext?.let { upNext ->
                    UpNextCard(
                        episode = upNext.episode,
                        onPlayNext = actions.onPlayNext,
                        onDismiss = actions.onDismissUpNext,
                    )
                }

                state.skippableSegment?.let { segment ->
                    SkipSegmentButton(
                        kind = segment.kind,
                        onClick = actions.onSkipSegment,
                        // Time-boxed: gone once the segment is, so it has to be announced rather
                        // than found by traversal.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            // The player is immersive and consumes no window insets, so the shared host's
            // chrome/gesture-bar rule resolves to zero and [SNACKBAR_PADDING] is the floor that
            // actually applies.
            JellyboostSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
                minimumBottomInset = SNACKBAR_PADDING,
            )
        }
    }

    // Outside the video `Box` so the controls cannot cover it; never in picture-in-picture, where a
    // panel would be wider than the window.
    if (!inPictureInPicture) {
        PanelHost(
            panel = openPanel,
            state = state,
            actions = actions,
            onDismiss = { openPanel = null },
        )
    }
}

/**
 * One exhaustive `when` rather than independently-gated hosts, which is what makes "one panel at a
 * time" a property of the code. The pickers belong here and not in the control bar, which disposes
 * its children along with itself.
 */
@Composable
private fun PanelHost(
    panel: PlayerPanel?,
    state: PlayerUiState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
) {
    val syncPlay = state.syncPlay

    when (panel) {
        null -> Unit

        PlayerPanel.AUDIO ->
            PlayerAudioDialog(state = state, onSelect = actions.onSelectAudio, onDismiss = onDismiss)

        PlayerPanel.SUBTITLES ->
            PlayerSubtitleDialog(state = state, onSelect = actions.onSelectSubtitle, onDismiss = onDismiss)

        PlayerPanel.SPEED ->
            PlayerSpeedDialog(state = state, onSelect = actions.onSelectSpeed, onDismiss = onDismiss)

        PlayerPanel.QUALITY ->
            PlayerQualityDialog(state = state, onSelect = actions.onSelectQuality, onDismiss = onDismiss)

        PlayerPanel.DISPLAY -> PlayerDisplayDialog(onDismiss = onDismiss)

        // Gated on membership as well as on the tap: a group that ends takes its sheet with it.
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

        // Gated on membership like the group sheet.
        PlayerPanel.QUEUE -> if (syncPlay.inGroup) SyncPlayQueueSheet(onDismiss = onDismiss)
    }
}

/**
 * Dark glass rather than `Modifier.glassSurface`: there is no Haze backdrop over the video frame, so
 * a 6%-white fill would leave white text on whatever the film happens to be showing.
 */
@Composable
private fun WaitingForGroupOverlay(
    syncPlay: PlayerSyncPlayState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                // Merged so the participant list arrives with the message rather than as a second
                // stop, and announced: a frozen frame says nothing to a user who cannot see it.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .background(color = OVERLAY_SCRIM, shape = RoundedCornerShape(Dimens.PanelRadius))
                .border(
                    width = GlassDefaults.HairlineWidth,
                    // The dark hairline explicitly: this panel's fill is literal black over the
                    // film, so a page-scheme edge would be black on black in the light theme.
                    color = GlassDefaults.DarkHairline,
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
 * The mid-playback rebuffer spinner. Four gates keep it honest, and each answers a frozen frame
 * better than a spinner would: the session still opening (`LoadingState`), the group waiting
 * ([WaitingForGroupOverlay]), a receiver holding the film (whose buffering this device cannot see),
 * and the chrome being visible (the transport row draws its own disc — the caller's guard).
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
        // Over raw video with no controls scrim behind it: the flat dark fill, not white@6% —
        // and with it the ink and edge, which cannot follow a page this pill is never drawn on.
        tint = VIDEO_GLASS_FILL,
        contentColor = Color.White,
        borderColor = GlassDefaults.DarkGhostBorder,
    )
}

/**
 * Replaces [VideoSurface] rather than hiding it — `CastPlayerHandle.player` is permanently `null`.
 * Two wanted consequences: the screen may sleep again (`keepScreenOn` belonged to the `PlayerView`),
 * and picture-in-picture has nothing to float, which `PlayerViewModel.publishPipState` disarms.
 *
 * The label is offset above centre because the transport row owns the middle of this screen;
 * measuring *from* the centre keeps it clear of the top bar in phone landscape too.
 */
@Composable
private fun CastingBackdrop(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val device = state.cast.deviceName ?: stringResource(R.string.player_cast_device_unnamed)

    Box(modifier = modifier) {
        // Fitted, not cropped: the artwork may be a wide backdrop or a 2:3 poster depending on what
        // the server holds, and cropping the second to a landscape screen shows a chin.
        JellyfinAsyncImage(
            url = state.artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            placeholderIcon = null,
        )
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
 * A paused player keeps its controls: a paused film with no controls looks like a frozen app.
 *
 * **While touch exploration is on the controls never hide**: no finite timeout is long enough to
 * read every control one swipe at a time.
 *
 * **[interactions] must stay in the effect key.** A key of `(armed, timeoutMs)` would not restart
 * the timer — neither changes on an interaction — so the bar would hide four seconds after it first
 * appeared no matter what was done with it.
 */
@Composable
private fun ControlsAutoHideEffect(
    visible: Boolean,
    isPlaying: Boolean,
    panelOpen: Boolean,
    interactions: Int,
    onHide: () -> Unit,
) {
    val timer =
        ControlsAutoHide(
            armed =
                controlsAutoHideArmed(
                    visible = visible,
                    isPlaying = isPlaying,
                    panelOpen = panelOpen,
                    touchExplorationEnabled = rememberTouchExplorationEnabled(),
                ),
            timeoutMs = recommendedControlsTimeoutMs(),
            interactions = interactions,
        )

    LaunchedEffect(timer) {
        if (!timer.armed) return@LaunchedEffect
        delay(timer.timeoutMs)
        onHide()
    }
}

/**
 * A `data class` because its equality *is* the `LaunchedEffect` key: it decides whether the running
 * timer survives a recomposition or is cancelled and restarted.
 *
 * @property timeoutMs the system's accessibility timeout, not a constant, so a change to "time to
 *   take action" restarts the timer as surely as a tap does.
 */
internal data class ControlsAutoHide(
    val armed: Boolean,
    val timeoutMs: Long,
    val interactions: Int,
)

/**
 * The timeout and interaction count are deliberately *not* arguments: they do not decide this
 * question, they only ride along in [ControlsAutoHide] as the rest of the effect's key.
 */
internal fun controlsAutoHideArmed(
    visible: Boolean,
    isPlaying: Boolean,
    panelOpen: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean = visible && isPlaying && !panelOpen && !touchExplorationEnabled

/**
 * `calculateRecommendedTimeoutMillis` is how "time to take action" reaches an app; it can return
 * "until dismissed", which is why the caller also has the touch-exploration escape hatch.
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
 * Observed rather than read once: TalkBack can be turned on mid-film, and a value captured at
 * composition would leave the auto-hide behaving as if it were still off.
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
 * `useController = false`: Media3's own controls would fight the Compose ones for touches.
 * `keepScreenOn` sits here rather than on the window so it follows the surface's lifetime. The
 * semantics are cleared because Media3's inner view hierarchy would otherwise leave stray
 * accessibility nodes over the video.
 */
@Composable
@UnstableApi
private fun VideoSurface(
    player: Player?,
    assSubtitleHandler: AssHandler?,
    modifier: Modifier = Modifier,
) {
    // Tracks what is *inside* the PlayerView, so it shares the view's lifetime rather than the
    // composition's: `factory` builds a fresh PlayerView whenever this composable re-enters.
    val attached = remember { mutableStateOf<AttachedAssOverlay?>(null) }

    AndroidView(
        modifier = modifier.clearAndSetSemantics { },
        factory = { context ->
            attached.value = null
            PlayerView(context).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = player
            view.applyAssOverlay(assSubtitleHandler, attached)
        },
        onRelease = { view ->
            view.player = null
            view.applyAssOverlay(null, attached)
        },
    )
}

@UnstableApi
private class AttachedAssOverlay(
    val handler: AssHandler,
    val view: AssSubtitleView,
)

/**
 * libass draws into its own view **inside** Media3's `SubtitleView`, which sits above the video
 * surface in the `PlayerView` hierarchy — the built-in cue output stays a sibling and keeps
 * rendering every format libass does not claim.
 *
 * Idempotent, and it removes only the view it added: the released handler's native memory is gone,
 * so drawing from it would be a crash, while `removeAllViews` would take Media3's own cue output
 * down with it.
 */
@UnstableApi
private fun PlayerView.applyAssOverlay(
    handler: AssHandler?,
    attached: MutableState<AttachedAssOverlay?>,
) {
    val current = attached.value
    if (current?.handler === handler) return
    val host = subtitleView ?: return
    current?.let { host.removeView(it.view) }
    attached.value =
        handler?.let { AttachedAssOverlay(it, AssSubtitleView(host.context, it).also(host::addView)) }
}

/**
 * Suspended in picture-in-picture ([enabled] `false`): asking for an orientation while the system is
 * resizing the window fights the animation.
 *
 * `SCREEN_ORIENTATION_USER`, not sensor-landscape: WCAG 1.3.4 allows a forced orientation only where
 * it is essential, and playback is not — a user whose system rotation is locked is not overridden.
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
        // The brightness swipe writes a per-window override (`PlayerGestureLayer`), and in a
        // single-activity app the window outlives the player: without this capture a film dimmed for
        // the night would leave every other screen dimmed too.
        val previousBrightness = window.attributes.screenBrightness

        // Deliberately never restored: `WindowCompat` has no getter, so any "previous value" would
        // be a literal, and `true` is the one value it must never be — the app is single-activity
        // and `enableEdgeToEdge()` set it false for the whole process.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
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
 * Every message is formatted with the receiver's name whether or not it mentions one: a format
 * argument a string has no placeholder for is simply dropped.
 */
@Composable
private fun PlayerMessage.snackbarText(cast: PlayerCastState): String =
    stringResource(textRes(), cast.deviceName ?: stringResource(R.string.player_cast_device_unnamed))

@Suppress("CyclomaticComplexMethod")
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
 * 0.62, not a lighter 0.45: against a bright poster black@45% composites to rgb(140), where the
 * white label over it reads 3.35:1. Black@62% takes the same worst case to rgb(97) and 6.20:1 — the
 * number `PlayerControls.SCRIM` uses.
 */
private val BACKDROP_SCRIM = Color.Black.copy(alpha = 0.62f)

/** Clears the transport row's 64 dp play button, whatever the viewport's height. */
private val CAST_LABEL_OFFSET = 88.dp

private val CAST_LABEL_ICON = 20.dp

/**
 * Over [OVERLAY_SCRIM]'s worst case (a white frame, composited to rgb(102)) white needs α ≥ 0.821
 * for 4.5:1; 0.85 gives 4.69:1.
 */
private const val DIM_ALPHA = 0.85f

/** Leaves room for the controls bar so a snackbar never covers the seek bar. */
private val SNACKBAR_PADDING = 96.dp

/** Clears the seek bar, so the skip offer never sits on top of the scrubber. */
private val SKIP_BUTTON_BOTTOM_PADDING = 112.dp

/** How long the controls linger after a tap while something is playing. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * The overlays only: [PlayerScreen] itself owns a `PlayerView`, a `hiltViewModel()` and an immersive
 * window, none of which a preview can supply.
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
