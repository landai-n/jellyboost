package dev.jellyboost.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.R
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.TrickplayTiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The transport controls drawn over the video.
 *
 * Scrubbing is deliberately local while the finger is down — the slider follows the touch and only
 * seeks on release, so a drag across a two-hour film does not fire hundreds of seeks at a
 * transcoding server. Since M9 that same drag also drives the trickplay preview, which is the whole
 * reason the scrub position is state rather than a callback.
 *
 * The bottom bar is width-capped and centred: on a 2560 px tablet a seek bar stretched edge to edge
 * puts the time readout and the pickers a hand-span apart from each other.
 */
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    position: StateFlow<PlaybackPosition>,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var openSheet by remember { mutableStateOf<PlayerSheet?>(null) }

    Box(modifier = modifier.fillMaxSize().background(SCRIM)) {
        TopBar(state = state, onBack = actions.onBack, modifier = Modifier.align(Alignment.TopStart))

        TransportRow(
            isPlaying = state.showsPlaying,
            onPlayPause = actions.onPlayPause,
            onSeekBy = actions.onSeekBy,
            modifier = Modifier.align(Alignment.Center),
        )

        BottomBar(
            state = state,
            position = position,
            actions = actions,
            onOpenSheet = { openSheet = it },
            modifier = Modifier.align(Alignment.BottomCenter),
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
        // Only when it is not 1×: a badge that is always there stops being information.
        if (!state.speed.isNormal) {
            Text(
                text = state.speed.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Dimens.SpaceMedium),
            )
        }
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
        // Casting and SyncPlay are mutually exclusive (docs/notes/chromecast-m12-plan.md, decision
        // 6), so in a group the button is not drawn at all rather than drawn and refused. Composed
        // out rather than given a hidden state: a group is a deliberate, long-lived thing, unlike
        // the receivers appearing and disappearing that the button animates through on its own.
        if (!state.syncPlay.inGroup) {
            CastRouteButton()
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
    position: StateFlow<PlaybackPosition>,
    actions: PlayerActions,
    onOpenSheet: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = MAX_BAR_WIDTH)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        Scrubber(
            position = position,
            durationMs = state.durationMs,
            trickplay = state.trickplay,
            onSeekTo = actions.onSeekTo,
        )

        // One subcomposition for the whole picker row, not one per button: the question — is there
        // room for words next to the icons? — is about the row's total width, and every button in it
        // has to answer it the same way.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val showLabels = showSheetButtonLabels(maxWidth)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Clock(position = position, durationMs = state.durationMs, modifier = Modifier.weight(1f))

                if (state.audioTracks.size > 1) {
                    SheetButton(
                        label = stringResource(R.string.player_audio),
                        onClick = { onOpenSheet(PlayerSheet.AUDIO) },
                        icon = Icons.Outlined.MusicNote,
                        showLabel = showLabels,
                    )
                }
                if (state.subtitleTracks.isNotEmpty()) {
                    SheetButton(
                        label = stringResource(R.string.player_subtitles),
                        onClick = { onOpenSheet(PlayerSheet.SUBTITLES) },
                        icon = Icons.Outlined.ClosedCaption,
                        showLabel = showLabels,
                    )
                }
                // No per-member playback rate exists in SyncPlay: playing faster than the group is
                // drifting from it, so the control is not offered rather than offered and refused
                // (docs/notes/syncplay-m11-plan.md, key decision 11). The same rule, for the same
                // reason, when the player in charge has no rate at all — which while casting is the
                // receiver's answer rather than ours (see [PlayerUiState.canSetSpeed]).
                if (!state.syncPlay.inGroup && state.canSetSpeed) {
                    SheetButton(
                        // The current rate replaces the word once it is not 1×, so the control says what
                        // it is doing without needing a second badge next to it.
                        label = if (state.speed.isNormal) stringResource(R.string.player_speed) else state.speed.label,
                        onClick = { onOpenSheet(PlayerSheet.SPEED) },
                        icon = Icons.Outlined.Speed,
                        showLabel = showLabels,
                    )
                }
                // Watching with other people is worth a permanent control, not a badge: it is where the
                // participants, the group's shuffle/repeat and the way out live (M11 Phase 3).
                if (state.syncPlay.inGroup) {
                    SheetButton(
                        label = stringResource(R.string.player_syncplay_group),
                        onClick = actions.onOpenGroupSheet,
                        icon = Icons.Outlined.Groups,
                        showLabel = showLabels,
                    )
                }
                // A control of its own rather than a row inside the group sheet: what the group watches
                // next is edited far more often than its shuffle mode, and two taps to reach a queue is
                // one too many while a film is running (M11 Phase 4). Offered only once the group has a
                // queue — before the first `PlayQueueUpdate` the sheet would have nothing in it.
                if (state.syncPlay.inGroup && state.syncPlay.hasQueue) {
                    SheetButton(
                        label = stringResource(R.string.player_syncplay_queue),
                        onClick = actions.onOpenQueueSheet,
                        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                        showLabel = showLabels,
                    )
                }
                // A downloaded file has no streaming bitrate to cap, so the picker would be inert.
                if (!state.isLocalPlayback) {
                    SheetButton(
                        label = stringResource(R.string.player_quality),
                        onClick = { onOpenSheet(PlayerSheet.QUALITY) },
                        icon = Icons.Outlined.HighQuality,
                        showLabel = showLabels,
                    )
                }
            }
        }
    }
}

/**
 * Whether the bottom bar's pickers have room to say what they are, given [maxWidth] — the width the
 * picker row itself was handed, inside the bar's padding.
 *
 * A device sweep put the fullest bar — five pickers plus the clock — at near-zero slack once its row
 * drops much below [LABELLED_BUTTONS_MIN_WIDTH]: the clock is what gives first, and then the last
 * picker clips off the end. Phone landscape (roughly 640–800 dp of viewport) and tablet portrait
 * (711 dp) both land there, so both go icon-only rather than squeezing the readout out; tablet
 * landscape, where the bar is capped at [MAX_BAR_WIDTH], stays labelled exactly as before.
 *
 * Pure and `internal` so the threshold is a unit test rather than a screenshot.
 */
internal fun showSheetButtonLabels(maxWidth: Dp): Boolean = maxWidth >= LABELLED_BUTTONS_MIN_WIDTH

/**
 * The elapsed / total readout.
 *
 * Its own composable so that collecting the position confines the twice-a-second recomposition to
 * two `Text`s, instead of dragging the picker row it sits in along with it (audit PERF-04).
 *
 * The elapsed text is `remember`ed against the *whole second*, not the raw position: `asClock()`
 * already truncates to seconds, so most of the twice-a-second ticks land on a second this composable
 * already formatted, and `String.format` had been re-run for a string identical to the one still on
 * screen (audit PERF-09). Keying on the second means the cached value — and the `Text` reading it —
 * only changes when the number displayed actually does.
 */
@Composable
private fun Clock(
    position: StateFlow<PlaybackPosition>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val current by position.collectAsStateWithLifecycle()
    val elapsedSeconds = current.positionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val elapsedText = remember(elapsedSeconds) { (elapsedSeconds * MILLIS_PER_SECOND).asClock() }
    val totalText = remember(durationMs) { durationMs.asClock() }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = elapsedText,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
        Text(
            text = " / $totalText",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = DIM_ALPHA),
        )
    }
}

/**
 * The seek bar, and the trickplay thumbnail that floats above it while a drag is in progress.
 *
 * The preview is positioned with a negative offset so it draws *outside* this composable's bounds:
 * reserving space for it would push the whole control bar up permanently, and a bar that jumps as
 * soon as a finger touches it is worse than no preview. Its horizontal position follows the drag and
 * is clamped to the bar, so a scrub to either end never leaves the thumbnail hanging off the screen —
 * the case a 2560 px tablet and a 1080 px phone disagree about.
 *
 * The position arrives as a flow and is collected here rather than passed down as a value: this is
 * one of the two places on the screen that wants it twice a second, and reading it any higher up
 * recomposes everything between (audit PERF-04).
 *
 * Two more things follow from a drag firing this at up to 90 Hz (audit PERF-09): the preview's clock
 * text is `remember`ed against the whole second the same way [Clock] is, rather than reformatted on
 * every frame of the drag, and the floating preview is positioned with the *lambda* `offset {}`
 * overload rather than `offset(x = Dp, y = Dp)` — the lambda is read during placement, not
 * composition, so a drag no longer has to recompose this modifier chain to move the preview; moving
 * it is layout's job.
 */
@Composable
private fun Scrubber(
    position: StateFlow<PlaybackPosition>,
    durationMs: Long,
    trickplay: TrickplayTiles?,
    onSeekTo: (Long) -> Unit,
) {
    val current by position.collectAsStateWithLifecycle()
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubFraction ?: current.progress(durationMs)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tiles = trickplay
        val scrubMs = (fraction * durationMs).toLong()
        val thumbnail = if (scrubFraction == null) null else tiles?.tileFor(scrubMs)

        if (tiles != null && thumbnail != null) {
            val previewWidth = TRICKPLAY_PREVIEW_HEIGHT * tiles.aspectRatio
            val slack = (maxWidth - previewWidth).coerceAtLeast(0.dp)
            val previewSeconds = scrubMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
            val previewText = remember(previewSeconds) { (previewSeconds * MILLIS_PER_SECOND).asClock() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                x = (maxWidth * fraction - previewWidth / 2).coerceIn(0.dp, slack).roundToPx(),
                                y = -(TRICKPLAY_PREVIEW_HEIGHT + PREVIEW_GAP + PREVIEW_LABEL_HEIGHT).roundToPx(),
                            )
                        },
            ) {
                TrickplayPreview(tiles = tiles, thumbnail = thumbnail)
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }

        Slider(
            value = fraction,
            onValueChange = { scrubFraction = it },
            onValueChangeFinished = {
                scrubFraction?.let { committed -> onSeekTo((committed * durationMs).toLong()) }
                scrubFraction = null
            },
        )
    }
}

/**
 * One picker in the bottom bar, with the word next to the icon or without it.
 *
 * The two forms carry the *same* semantics on purpose: labelled, the visible [Text] names the button
 * and the icon is decorative, so its description stays null; icon-only, there is no text left to read
 * out, so the description moves onto the icon. Either way TalkBack announces "Audio", and the narrow
 * form loses nothing but the pixels.
 */
@Composable
private fun SheetButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    showLabel: Boolean = true,
) {
    if (!showLabel) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
        }
        return
    }

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
private const val MILLIS_PER_SECOND = 1_000L
private const val SKIP_BACK_MS = 10_000L
private const val SKIP_FORWARD_MS = 30_000L
private const val DIM_ALPHA = 0.7f

private val SCRIM = Color.Black.copy(alpha = 0.35f)
private val PRIMARY_ICON = 64.dp
private val SECONDARY_ICON = 40.dp

/** Wide enough for a 21:9 film's controls, narrow enough to stay one glance on a tablet. */
private val MAX_BAR_WIDTH = 1000.dp

/**
 * Below this much room for the picker row, the pickers drop their words — see
 * [showSheetButtonLabels] for what the sweep measured.
 */
private val LABELLED_BUTTONS_MIN_WIDTH = 840.dp

private val PREVIEW_GAP = 8.dp
private val PREVIEW_LABEL_HEIGHT = 18.dp

/** The fullest bar there is — five pickers plus the clock — at a phone's landscape width. */
@Preview(name = "Bottom bar · phone landscape", widthDp = 800, heightDp = 360)
@Composable
private fun BottomBarPhoneLandscapePreview() {
    BottomBarPreview()
}

/** The same bar on the tablet, where there is room for the words. */
@Preview(name = "Bottom bar · tablet landscape", widthDp = 1138, heightDp = 640)
@Composable
private fun BottomBarTabletLandscapePreview() {
    BottomBarPreview()
}

@Composable
private fun BottomBarPreview() {
    JellyfinTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            BottomBar(
                state =
                    PlayerUiState(
                        isLoading = false,
                        title = "The Original",
                        durationMs = 45.minutes.inWholeMilliseconds,
                        audioTracks =
                            listOf(
                                previewTrack(index = 1, label = "English"),
                                previewTrack(index = 2, label = "Français"),
                            ),
                        subtitleTracks = listOf(previewTrack(index = 3, label = "English (SDH)")),
                        // In a group with a queue: audio, subtitles, group, queue and quality — the
                        // five-picker worst case the narrow bar has to survive.
                        syncPlay = PlayerSyncPlayState(inGroup = true, groupName = "Film night", hasQueue = true),
                    ),
                position = MutableStateFlow(PlaybackPosition(positionMs = 12.minutes.inWholeMilliseconds)),
                actions = previewActions(),
                onOpenSheet = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun previewTrack(
    index: Int,
    label: String,
) = PlaybackTrack(index = index, label = label, language = null, codec = null)

private fun previewActions() =
    PlayerActions(
        onPlayPause = {},
        onSeekTo = {},
        onSeekBy = {},
        onSelectAudio = {},
        onSelectSubtitle = {},
        onSelectQuality = {},
        onSelectSpeed = {},
        onSkipSegment = {},
        onBack = {},
    )
