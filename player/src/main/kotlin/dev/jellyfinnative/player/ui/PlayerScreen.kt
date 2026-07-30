package dev.jellyfinnative.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.player.R
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
    val snackbarHostState = remember { SnackbarHostState() }
    // Rebuilt per composition, these nine method references are nine new unstable lambdas, and every
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
            )
        }
    val message = state.userMessage?.let { stringResource(it.textRes()) }
    var controlsVisible by remember { mutableStateOf(true) }
    val inPictureInPicture = pipState.isInPictureInPicture

    ImmersiveLandscapeEffect(enabled = !inPictureInPicture)

    // The position poll that drives the seek bar only runs while this screen exists. Playback and
    // progress reporting are deliberately not tied to it — that is what lets the app be
    // backgrounded without the film stopping (M9).
    DisposableEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onDispose { viewModel.setScreenVisible(false) }
    }

    // Controls get out of the way on their own while something is playing; a paused player keeps
    // them, because a paused film with no controls looks like a frozen app.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.hasEnded) {
        if (state.hasEnded) onBack()
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(player = player, modifier = Modifier.fillMaxSize())

        // Bare video in the floating window; the notification carries the transport controls.
        if (inPictureInPicture) return@Box

        PlayerGestureLayer(
            onToggleControls = { controlsVisible = !controlsVisible },
            onSeekBy = actions.onSeekBy,
        )

        when {
            state.errorMessage != null ->
                ErrorState(
                    message = requireNotNull(state.errorMessage),
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = onBack,
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

        state.skippableSegment?.let { segment ->
            SkipSegmentButton(
                kind = segment.kind,
                onClick = actions.onSkipSegment,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Dimens.SpaceExtraLarge, bottom = SKIP_BUTTON_BOTTOM_PADDING),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SNACKBAR_PADDING),
        )
    }
}

/**
 * The "Skip intro" / "Skip outro" offer.
 *
 * A solid button rather than a subtle one: it appears for a bounded window, it is competing with
 * the film for attention, and every other client on the platform draws it the same way — a user who
 * has to look for it has already missed it.
 */
@Composable
private fun SkipSegmentButton(
    kind: MediaSegmentKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Filled.SkipNext, contentDescription = null)
        Text(
            text =
                stringResource(
                    when (kind) {
                        MediaSegmentKind.INTRO -> R.string.player_skip_intro
                        MediaSegmentKind.OUTRO -> R.string.player_skip_outro
                    },
                ),
            modifier = Modifier.padding(start = Dimens.SpaceExtraSmall),
        )
    }
}

/**
 * The video output.
 *
 * `useController = false`: the transport controls are Compose, and Media3's own would fight them
 * for touches. `keepScreenOn` is set here rather than on the window so it follows the surface's
 * lifetime exactly.
 */
@Composable
@UnstableApi
private fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
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
 * Puts the window into immersive landscape for as long as the player is composed.
 *
 * The previous orientation and system-bar behaviour are captured and restored on dispose; the
 * project's test device is a tablet, where getting this wrong leaves the whole app sideways.
 *
 * Suspended in picture-in-picture ([enabled] `false`): a floating window has no system bars to hide
 * and no orientation to force, and asking for landscape while the system is resizing the window
 * fights the animation.
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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, previousDecorFitsSystemWindows)
            activity.requestedOrientation = previousOrientation
        }
    }
}

private fun PlayerMessage.textRes(): Int =
    when (this) {
        PlayerMessage.SwitchedToTranscode -> R.string.player_message_transcode
        PlayerMessage.RetryingAtLowerQuality -> R.string.player_message_lower_quality
        PlayerMessage.RestartedForTrackChange -> R.string.player_message_track_restart
        PlayerMessage.TrackUnavailableOffline -> R.string.player_message_track_offline
        PlayerMessage.PlaybackFailed -> R.string.player_message_failed
    }

/** Leaves room for the controls bar so a snackbar never covers the seek bar. */
private val SNACKBAR_PADDING = 96.dp

/** Clears the seek bar, so the skip offer never sits on top of the scrubber. */
private val SKIP_BUTTON_BOTTOM_PADDING = 112.dp

/** How long the controls linger after a tap while something is playing. */
private const val CONTROLS_TIMEOUT_MS = 4_000L
