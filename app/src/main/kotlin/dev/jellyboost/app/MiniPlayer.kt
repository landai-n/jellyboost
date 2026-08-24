package dev.jellyboost.app

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.popShadow

/**
 * The docked bar the chrome shows whenever music is loaded and the user is not already looking at it.
 * Tinted [GlassDefaults.BottomNavFill] rather than the lighter in-content fill, because it floats
 * over full-bleed artwork as often as the nav pill does.
 */
@Composable
internal fun MiniPlayer(
    state: MusicPlaybackState.Active,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentItem ?: return
    val shape = RoundedCornerShape(Dimens.RadiusXl)
    val progressFraction =
        if (state.durationMs > 0L) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = MiniPlayerMaxWidth)
                .heightIn(min = MiniPlayerHeight)
                .popShadow(shape)
                .glassSurface(shape = shape, tint = GlassDefaults.BottomNavFill),
    ) {
        // Decoration, not a second seek bar: no semantics of its own, and the real scrubber is on
        // `NowPlayingScreen`.
        Box(modifier = Modifier.fillMaxWidth().height(ProgressLineHeight).background(ProgressTrackColor)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }

        MiniPlayerRow(
            track = track,
            isPlaying = state.isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onClick = onClick,
        )
    }
}

/**
 * [MiniPlayer] with the swipe that ends the session. `onDismiss` must *stop* rather than hide: the
 * bar is shown off the queue's own state, so a hide would come back on the next recomposition.
 */
@Composable
internal fun DismissableMiniPlayer(
    state: MusicPlaybackState.Active,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val dismissLabel = stringResource(R.string.mini_player_dismiss)

    // The key *is* the guard against re-firing: keyed on the value, the recompositions the settle
    // animation drives cannot re-enter it.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onDismiss()
    }

    // Not snapped back after `onDismiss` — resetting the offset mid-exit reads as a bounce; disposal
    // of the `AnimatedVisibility` is what resets it. This only covers what disposal misses: a new
    // queue starting while this bar is still composed, without the state passing through Idle.
    val sessionKey = state.queue.firstOrNull()?.id to state.queue.size
    LaunchedEffect(sessionKey) { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }

    SwipeToDismissBox(
        state = dismissState,
        // `SwipeToDismissBox` publishes no accessibility action of its own, and a gesture no screen
        // reader can perform is a control only some users have.
        backgroundContent = {},
        modifier =
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(label = dismissLabel) {
                            onDismiss()
                            true
                        },
                    )
            },
    ) {
        MiniPlayer(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onClick = onClick,
        )
    }
}

@Composable
private fun MiniPlayerRow(
    track: JellyfinItem,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.mini_player_open_now_playing), onClick = onClick)
                .padding(horizontal = Dimens.SpaceMedium, vertical = Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        JellyfinAsyncImage(
            url = track.primaryImageUrl,
            contentDescription = null,
            modifier = Modifier.size(ArtSize).clip(RoundedCornerShape(Dimens.CardCornerRadius)),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = TitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            track.displaySubtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = SubtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        MiniPlayerTransport(
            isPlaying = isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
        )
    }
}

/** Previous / play-pause / next — the bar is very often the only transport on screen. */
@Composable
private fun MiniPlayerTransport(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onPrevious) {
        Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = stringResource(R.string.mini_player_previous),
            tint = tint,
        )
    }
    IconButton(onClick = onTogglePlayPause) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription =
                stringResource(if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play),
            tint = tint,
        )
    }
    IconButton(onClick = onNext) {
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.mini_player_next),
            tint = tint,
        )
    }
}

/** Above its own top progress line. */
internal val MiniPlayerHeight = 64.dp

/** Between the bar and whatever floats below it — the pill, or the window edge. */
internal val MiniPlayerGap = 12.dp

private val MiniPlayerMaxWidth = 640.dp

private val ArtSize = 44.dp

private val ProgressLineHeight = 2.dp

private val ProgressTrackColor = Color.White.copy(alpha = 0.12f)

private val TitleStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W600)

private val SubtitleStyle = TextStyle(fontSize = 12.sp)

@Preview(name = "MiniPlayer", showBackground = true, backgroundColor = 0xFF101010, widthDp = 400)
@Composable
private fun MiniPlayerPreview() {
    JellyfinTheme {
        MiniPlayer(
            state =
                MusicPlaybackState.Active(
                    queue = listOf(previewTrack()),
                    currentIndex = 0,
                    isPlaying = true,
                    positionMs = 90_000L,
                    durationMs = 225_000L,
                    shuffleEnabled = false,
                    repeatMode = MusicRepeatMode.OFF,
                ),
            onTogglePlayPause = {},
            onPrevious = {},
            onNext = {},
            onClick = {},
            modifier = Modifier.padding(Dimens.SpaceMedium),
        )
    }
}

private fun previewTrack() =
    JellyfinItem(
        id = "t1",
        name = "Fake Plastic Trees (a genuinely quite long title to exercise the marquee)",
        type = ItemType.AUDIO,
        artists = listOf("Radiohead"),
        downloadState = DownloadState.NotDownloaded,
    )
