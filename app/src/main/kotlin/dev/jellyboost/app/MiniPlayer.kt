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
 * The docked bar the app chrome shows whenever music is loaded and the user is not already looking
 * at it — artwork, title/artist, previous, play/pause, next, a thin progress line, and a
 * tap-through to [dev.jellyboost.core.common.Routes.NowPlaying].
 *
 * Visual language matches [GlassBottomNav]: a floating glass bar, [popShadow] under it,
 * [GlassDefaults.BottomNavFill] rather than the lighter in-content [GlassDefaults.Fill] — the same
 * reasoning applies here, since this bar floats over full-bleed artwork just as often as the nav
 * pill does.
 *
 * @param state the loaded queue; this composable is never asked to draw [MusicPlaybackState.Idle] —
 *   [AppScaffold] only shows it while [showsMiniPlayer] says so.
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
        // The nice-to-have progress line: a track this thin reads as decoration, not as a second,
        // less precise seek bar, so it carries no semantics of its own — the full scrubber lives
        // on `NowPlayingScreen`, one tap away.
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
 * [MiniPlayer] with the swipe that ends the session — the repo's first gesture-driven interaction,
 * and deliberately M3's stock [SwipeToDismissBox] rather than a hand-rolled drag.
 *
 * Both directions are enabled (the component's own default): `start`/`end` are layout-direction
 * relative, so allowing one would mean an RTL reader swiping the opposite way from an LTR one for
 * no reason the gesture itself expresses.
 *
 * @param onDismiss ends the session — a *stop*, not a hide, so the bar does not come back the
 *   moment anything recomposes. [AppScaffold] shows this bar off the queue's own state, so nothing
 *   here hides anything itself.
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

    // Once per transition out of `Settled`, and the key *is* the guard against re-firing: an effect
    // keyed on the value restarts only when that value changes, so the recompositions the settle
    // animation drives cannot re-enter it.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onDismiss()
    }

    // Deliberately *not* snapped back after `onDismiss`: the bar is already on its way out through
    // `AppScaffold`'s `AnimatedVisibility`, and resetting the offset mid-exit reads as a bounce.
    // What resets it for the next session is disposal — the exit empties that `AnimatedVisibility`
    // and takes `dismissState` with it, so the next queue composes a fresh one. That is the
    // load-bearing mechanism. This effect only covers what disposal misses: a new queue starting
    // while this bar is still composed (a dismiss racing a `play()` elsewhere), which swaps the
    // session without the state ever passing through Idle.
    val sessionKey = state.queue.firstOrNull()?.id to state.queue.size
    LaunchedEffect(sessionKey) { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }

    SwipeToDismissBox(
        state = dismissState,
        // A gesture no screen reader can perform is a control only some users have, so the same
        // verb is published as a custom action; `SwipeToDismissBox` adds none of its own.
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

/** The bar's one content row: artwork, title/artist, [MiniPlayerTransport] — the tap target. */
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

/**
 * Previous / play-pause / next.
 *
 * Previous is here because the bar is very often the only transport on screen: it shows on the
 * pushed music screens too ([showsMiniPlayer]), and without it, going back a track would mean
 * opening the full now-playing view to reach the one button. It sits *before* play/pause, the
 * order every transport row in the app draws (`NowPlayingTransportRow`, `PlayerControls`), so the
 * glyphs read left-to-right as the timeline does.
 *
 * The tints are `onSurface` — the theme token, not a raw `Color.White`. Identical pixels on this
 * dark-only scheme (`JellyfinColors.OnSurface` *is* white), stated as the role so the bar's
 * glyphs move with the palette rather than being pinned outside it.
 */
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

/** Height of the docked bar, above its own top progress line — comparable to [BottomNavHeight]. */
internal val MiniPlayerHeight = 64.dp

/** The gap left between the mini-player and whatever floats below it (the pill, or the window edge). */
internal val MiniPlayerGap = 12.dp

/** Caps the bar's width on a wide tablet, matching the queue sheet's own [SHEET_MAX_WIDTH]-style cap. */
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
