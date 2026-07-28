package dev.jellyfinnative.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.jellyfinnative.core.ui.component.ErrorState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.player.R

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
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.videoPlayer.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { stringResource(it.textRes()) }

    ImmersiveLandscapeEffect()

    // The position poll that drives the seek bar only runs while this screen exists.
    DisposableEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onDispose { viewModel.setScreenVisible(false) }
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

        when {
            state.errorMessage != null ->
                ErrorState(
                    message = requireNotNull(state.errorMessage),
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = onBack,
                )

            state.isLoading -> LoadingState(modifier = Modifier.align(Alignment.Center))

            else ->
                PlayerControls(
                    state = state,
                    actions =
                        PlayerActions(
                            onPlayPause = viewModel::togglePlayPause,
                            onSeekTo = viewModel::seekTo,
                            onSeekBy = viewModel::seekBy,
                            onSelectAudio = viewModel::selectAudioTrack,
                            onSelectSubtitle = viewModel::selectSubtitleTrack,
                            onSelectQuality = viewModel::selectQuality,
                            onBack = onBack,
                        ),
                )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SNACKBAR_PADDING),
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
 */
@Composable
private fun ImmersiveLandscapeEffect() {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(activity) {
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
        PlayerMessage.PlaybackFailed -> R.string.player_message_failed
    }

/** Leaves room for the controls bar so a snackbar never covers the seek bar. */
private val SNACKBAR_PADDING = 96.dp
