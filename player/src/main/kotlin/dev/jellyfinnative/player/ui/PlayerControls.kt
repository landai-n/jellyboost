package dev.jellyfinnative.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.R
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * The transport controls drawn over the video.
 *
 * Scrubbing is deliberately local while the finger is down — the slider follows the touch and only
 * seeks on release, so a drag across a two-hour film does not fire hundreds of seeks at a
 * transcoding server.
 */
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var openSheet by remember { mutableStateOf<PlayerSheet?>(null) }

    Box(modifier = modifier.fillMaxSize().background(SCRIM)) {
        TopBar(state = state, onBack = actions.onBack, modifier = Modifier.align(Alignment.TopStart))

        TransportRow(
            isPlaying = state.isPlaying,
            onPlayPause = actions.onPlayPause,
            onSeekBy = actions.onSeekBy,
            modifier = Modifier.align(Alignment.Center),
        )

        BottomBar(
            state = state,
            onSeekTo = actions.onSeekTo,
            onOpenSheet = { openSheet = it },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }

    PlayerSheetHost(
        sheet = openSheet,
        state = state,
        actions = actions,
        onDismiss = { openSheet = null },
    )
}

@Composable
private fun TopBar(
    state: PlayerUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().systemBarsPadding().padding(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.player_back),
                tint = Color.White,
            )
        }
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        state.playMethod?.let { method ->
            // On screen on purpose: the M5 definition of done is "the server shows the expected
            // play method", and having it here makes that check possible without the dashboard.
            Text(
                text = stringResource(method.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Dimens.SpaceMedium),
            )
        }
    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        IconButton(onClick = { onSeekBy(-SKIP_BACK_MS) }) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.player_rewind),
                tint = Color.White,
                modifier = Modifier.size(SECONDARY_ICON),
            )
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription =
                    stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                tint = Color.White,
                modifier = Modifier.size(PRIMARY_ICON),
            )
        }
        IconButton(onClick = { onSeekBy(SKIP_FORWARD_MS) }) {
            Icon(
                imageVector = Icons.Filled.Forward30,
                contentDescription = stringResource(R.string.player_forward),
                tint = Color.White,
                modifier = Modifier.size(SECONDARY_ICON),
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: PlayerUiState,
    onSeekTo: (Long) -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubPosition by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().systemBarsPadding().padding(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        Slider(
            value = scrubPosition ?: state.progress,
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                scrubPosition?.let { fraction -> onSeekTo((fraction * state.durationMs).toLong()) }
                scrubPosition = null
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.positionMs.asClock(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Text(
                text = " / ${state.durationMs.asClock()}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = DIM_ALPHA),
                modifier = Modifier.weight(1f),
            )

            if (state.audioTracks.size > 1) {
                SheetButton(
                    label = stringResource(R.string.player_audio),
                    onClick = { onOpenSheet(PlayerSheet.AUDIO) },
                    icon = Icons.Outlined.MusicNote,
                )
            }
            if (state.subtitleTracks.isNotEmpty()) {
                SheetButton(
                    label = stringResource(R.string.player_subtitles),
                    onClick = { onOpenSheet(PlayerSheet.SUBTITLES) },
                    icon = Icons.Outlined.ClosedCaption,
                )
            }
            // A downloaded file has no streaming bitrate to cap, so the picker would be inert.
            if (!state.isLocalPlayback) {
                SheetButton(
                    label = stringResource(R.string.player_quality),
                    onClick = { onOpenSheet(PlayerSheet.QUALITY) },
                    icon = Icons.Outlined.HighQuality,
                )
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White)
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = Dimens.SpaceExtraSmall),
        )
    }
}

private fun PlayMethod.labelRes(): Int =
    when (this) {
        PlayMethod.DIRECT_PLAY -> R.string.player_method_direct_play
        PlayMethod.DIRECT_STREAM -> R.string.player_method_direct_stream
        PlayMethod.TRANSCODE -> R.string.player_method_transcode
    }

/** `1:23:45` above an hour, `12:34` below it. */
internal fun Long.asClock(): String {
    val duration = coerceAtLeast(0L).milliseconds
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % MINUTES_PER_HOUR
    val seconds = duration.inWholeSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0L -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val SKIP_BACK_MS = 10_000L
private const val SKIP_FORWARD_MS = 30_000L
private const val DIM_ALPHA = 0.7f

private val SCRIM = Color.Black.copy(alpha = 0.35f)
private val PRIMARY_ICON = 64.dp
private val SECONDARY_ICON = 40.dp
