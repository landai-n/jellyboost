package dev.jellyboost.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.glassSurface
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
 *
 * ### The 2026 refresh
 * Everything here is glass over the film — circles for the seek buttons and the chrome, a pill for
 * each picker — with exactly one solid surface on the screen: the white play/pause disc. That is the
 * refresh's rule for primary actions (DECISIONS.md 2026-08-01, "primary action buttons are white"),
 * and over a moving image it is also the only thing that stays findable at a glance. No control was
 * added, removed or rewired in the restyle; the seek amounts, the picker set, the sheet host and the
 * label threshold are the M9–M12 ones.
 *
 * The glass here is *flat* dark glass — [VIDEO_GLASS_FILL] plus the standard hairline — not a Haze
 * blur. The video is a `SurfaceView` whose pixels are composited by the system and never reach the
 * recorded backdrop layer, so a blur over it samples nothing and rendered as an opaque `#101010`
 * disc while still paying a per-frame blur pass ([WaitingForGroupOverlay] in `PlayerScreen`
 * documents the same reasoning). `JellyfinNavHost` nulls `LocalHazeState` for the player subtree,
 * which routes every `glassSurface` in it onto the flat-fill fallback; the tints below choose what
 * that flat fill is.
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
        modifier =
            modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.player_back),
            onClick = onBack,
            size = CHROME_BUTTON,
            // Full white rather than the chrome default of white@80%: this button is read against a
            // moving image, not against the app's background.
            tint = Color.White,
            surfaceTint = VIDEO_GLASS_FILL,
        )
        TitleStack(label = state.title, modifier = Modifier.weight(1f))
        // Only when it is not 1×: a badge that is always there stops being information. Kept beside
        // the method tag rather than folded into the speed picker, because the picker is composed
        // out entirely in a group and while a receiver has no rate of its own.
        if (!state.speed.isNormal) {
            TagPill(text = state.speed.label)
        }
        state.playMethod?.let { method ->
            // On screen on purpose: the M5 definition of done is "the server shows the expected
            // play method", and having it here makes that check possible without the dashboard.
            TagPill(text = playbackMethodTag(method = method, videoHeight = state.videoHeight))
        }
        // Casting and SyncPlay are mutually exclusive (docs/notes/chromecast-m12-plan.md, decision
        // 6), so in a group the button is not drawn at all rather than drawn and refused. Composed
        // out rather than given a hidden state: a group is a deliberate, long-lived thing, unlike
        // the receivers appearing and disappearing that the button animates through on its own.
        if (!state.syncPlay.inGroup) {
            CastRouteButton(
                // The frame the `GlassIconButton`s in this bar reserve, with the circle drawn at
                // the bar's own button size inside it — see `JellyfinButtons.kt`.
                modifier = Modifier.size(Dimens.MinTouchTarget),
                glassContainer = true,
                size = CHROME_BUTTON,
                surfaceTint = VIDEO_GLASS_FILL,
            )
        }
    }
}

/**
 * The two-line lockup at the head of the top bar: what is playing, and which episode of what.
 *
 * The second line is *derived* rather than plumbed. `PlayerViewModel.loadTitleAndArtwork` already
 * joins the item's title and its episode line with [PLAYER_LABEL_SEPARATOR] into the single label
 * this bar used to draw, so the two lines the refresh wants are recovered by splitting that label
 * back apart at the separator it was built with — no second field on the state, and nothing new
 * fetched. An item with no episode line (a film) joins to one part and draws one line.
 */
@Composable
private fun TitleStack(
    label: String,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = remember(label) { label.asTitleAndSubtitle() }

    Column(modifier = modifier) {
        Text(
            text = title,
            style = TITLE_STYLE,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = SUBTITLE_STYLE,
                color = Color.White.copy(alpha = SUBTITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A small tinted tag — the playback method, and the playback rate when it is not 1×.
 *
 * Primary-tinted rather than glass: these two say something *about the stream* rather than offering
 * an action, and the refresh reserves glass for surfaces the user can press.
 */
@Composable
private fun TagPill(text: String) {
    val primary = MaterialTheme.colorScheme.primary

    Text(
        text = text,
        style = TAG_STYLE,
        color = TAG_TEXT,
        maxLines = 1,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(primary.copy(alpha = TAG_FILL_ALPHA))
                .border(
                    width = TAG_BORDER_WIDTH,
                    color = primary.copy(alpha = TAG_BORDER_ALPHA),
                    shape = CircleShape,
                ).padding(horizontal = TAG_PADDING_HORIZONTAL, vertical = TAG_PADDING_VERTICAL),
    )
}

/**
 * The method tag's words: "TRANSCODING 1080P", or just the method.
 *
 * The height is [PlayerUiState.videoHeight] — the size the decoder reports for the video that is
 * actually on screen, which for a transcode is what the server chose to send. It is already on the
 * state (it drives the picture-in-picture aspect ratio), so the tag costs nothing to assemble; it is
 * `0` before the first frame and while a receiver has the film, and the tag is then the method alone
 * rather than a made-up resolution.
 */
@Composable
private fun playbackMethodTag(
    method: PlayMethod,
    videoHeight: Int,
): String {
    val label = stringResource(method.labelRes())
    val text =
        if (videoHeight > 0) {
            stringResource(R.string.player_method_tag_height, label, videoHeight)
        } else {
            label
        }
    return text.uppercase(Locale.getDefault())
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
        SeekButton(
            icon = Icons.Filled.Replay10,
            contentDescription = stringResource(R.string.player_rewind),
            onClick = { onSeekBy(-SKIP_BACK_MS) },
        )
        PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
        SeekButton(
            icon = Icons.Filled.Forward30,
            contentDescription = stringResource(R.string.player_forward),
            onClick = { onSeekBy(SKIP_FORWARD_MS) },
        )
    }
}

/** One of the two glass seek circles either side of the play button. */
@Composable
private fun SeekButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(SEEK_BUTTON).glassSurface(CircleShape, tint = VIDEO_GLASS_FILL),
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
            ),
        // A circle sized to its glyph has no room for Material's default button padding.
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(SEEK_ICON),
        )
    }
}

/**
 * The one solid surface on the screen.
 *
 * White fill with a `#101010` glyph, the refresh's primary-action treatment: over a film that is
 * both the least ambiguous shape available and the only control a user hunts for while the picture
 * is moving.
 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(PLAY_BUTTON),
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = PLAY_GLYPH,
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription =
                stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            modifier = Modifier.size(PLAY_ICON),
        )
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
                // Each SheetChip already carries an invisible Dimens.MinTouchTarget (48dp) frame
                // around its 32dp visual (see that composable's KDoc), so any arrangement spacing
                // here stacks on top of the frames' own gap — 0dp yields 16dp between circles.
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Clock(position = position, durationMs = state.durationMs, modifier = Modifier.weight(1f))

                if (state.audioTracks.size > 1) {
                    SheetChip(
                        label = stringResource(R.string.player_audio),
                        onClick = { onOpenSheet(PlayerSheet.AUDIO) },
                        icon = Icons.Outlined.MusicNote,
                        showLabel = showLabels,
                    )
                }
                if (state.subtitleTracks.isNotEmpty()) {
                    SheetChip(
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
                    SheetChip(
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
                    SheetChip(
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
                    SheetChip(
                        label = stringResource(R.string.player_syncplay_queue),
                        onClick = actions.onOpenQueueSheet,
                        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                        showLabel = showLabels,
                    )
                }
                // A downloaded file has no streaming bitrate to cap, so the picker would be inert.
                if (!state.isLocalPlayback) {
                    SheetChip(
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
 * The 2026 refresh's chips are *narrower* than the text buttons that sweep measured, so the
 * threshold is now conservative where it used to be tight. It is deliberately unchanged: it is the
 * same devices either side of it, and moving a pinned number for extra slack buys nothing.
 *
 * Pure and `internal` so the threshold is a unit test rather than a screenshot.
 */
internal fun showSheetButtonLabels(maxWidth: Dp): Boolean = maxWidth >= LABELLED_BUTTONS_MIN_WIDTH

/**
 * The top bar's single label, split back into the title and the episode line it was joined from.
 *
 * The join happens in `PlayerViewModel.loadTitleAndArtwork` and is the only thing that ever produces
 * [PlayerUiState.title], so the first separator is the one the join inserted: everything before it
 * is `JellyfinItem.displayTitle`, everything after it is `displaySubtitle`, whose own middots (a
 * series name and an episode label are themselves joined that way) survive intact in the second
 * line.
 *
 * @return the title, and the second line or `null` when there is none — a film, or an item whose
 *   subtitle is blank.
 */
internal fun String.asTitleAndSubtitle(): Pair<String, String?> {
    val separator = indexOf(PLAYER_LABEL_SEPARATOR)
    if (separator < 0) return this to null

    val subtitle = substring(separator + PLAYER_LABEL_SEPARATOR.length)
    return substring(0, separator) to subtitle.takeIf { it.isNotBlank() }
}

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
 *
 * Tabular figures ([CLOCK_STYLE]): a proportional `1` is narrower than a `0`, and a readout that
 * shifts sideways twice a second is the sort of thing nobody can name but everybody notices.
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
            style = CLOCK_STYLE,
            color = Color.White,
        )
        Text(
            text = " / $totalText",
            style = CLOCK_STYLE,
            color = Color.White.copy(alpha = CLOCK_DIM_ALPHA),
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
 *
 * ### Why the slider keeps its slots rather than being rebuilt
 * The 2026 refresh wants a buffered segment behind the played one, which no `SliderColors` can
 * express — but everything else about a slider (the drag, the press-anywhere-to-seek, the
 * accessibility actions, the RTL mapping) is exactly what is wanted. So the M3 `Slider` stays and
 * only its `track` and `thumb` are supplied: [ScrubberTrack] draws the three layers, [ScrubberThumb]
 * the 14dp disc. Both read the same `fraction` this composable already holds rather than the
 * `SliderState` handed to the slot, because during a drag that local value *is* the one on screen.
 *
 * The slot overload is still `@ExperimentalMaterial3Api` in Material3 1.4 — the same opt-in the
 * app's bottom sheets and search bars already take.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                    style = CLOCK_STYLE,
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
            thumb = { ScrubberThumb() },
            track = {
                ScrubberTrack(
                    fraction = fraction,
                    bufferedFraction = current.bufferedProgress(durationMs),
                )
            },
        )
    }
}

/**
 * The seek bar's three layers: the whole length, how much of it is in the buffer, and how much has
 * been played.
 *
 * The buffered layer is what the refresh adds — [PlaybackPosition.bufferedMs] has been published by
 * the position tracker since M9 and had no way to be drawn through `SliderColors`. It is measured
 * from zero rather than from the play head, matching every other player: on an HLS transcode the
 * gap between the two edges is the answer to "why did it stall", and starting it at the play head
 * would hide exactly the case where it is short.
 */
@Composable
private fun ScrubberTrack(
    fraction: Float,
    bufferedFraction: Float,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .clip(CircleShape)
                .background(TRACK_COLOR),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(BUFFERED_COLOR),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The scrub handle: a 14dp white disc.
 *
 * The shadow is deliberately much smaller than `JellyfinElevation`'s card shadow — those are tuned
 * for surfaces a hand across, and at this size the same elevation draws a grey smudge rather than a
 * lift. The touch target is unaffected: the slider's own gesture area is the full bar, not the disc.
 */
@Composable
private fun ScrubberThumb() {
    Box(
        modifier =
            Modifier
                .size(THUMB_SIZE)
                .shadow(
                    elevation = THUMB_SHADOW,
                    shape = CircleShape,
                    ambientColor = THUMB_SHADOW_COLOR,
                    spotColor = THUMB_SHADOW_COLOR,
                ).background(color = Color.White, shape = CircleShape),
    )
}

/**
 * One picker in the bottom bar: a glass pill with the word next to the icon, or a glass circle
 * without it.
 *
 * The two forms carry the *same* semantics on purpose: labelled, the visible [Text] names the button
 * and the icon is decorative, so its description stays null; icon-only, there is no text left to read
 * out, so the description moves onto the icon. Either way TalkBack announces "Audio", and the narrow
 * form loses nothing but the pixels.
 *
 * Built on `Box`/`Row`, not M3 `Button`, for the same reason as every button in
 * `core/ui`'s `JellyfinButtons.kt` (see that file's header): `Button` delegates to `Surface`, which
 * inserts `Modifier.minimumInteractiveComponentSize()` *inside* the caller's modifier chain, so it
 * reports 48dp regardless of the size the caller asked for — a caller's `.size(32.dp).glassSurface(…)`
 * clips/blurs/outlines that 48dp node, not the 32dp one, and adjacent chips overlap. The invisible
 * outer `Box` reserves [Dimens.MinTouchTarget] for touch, the inner one draws the glass at its
 * declared [CHIP_HEIGHT], and the click target sits inside that inner clip so the ripple is bounded
 * by the visible shape rather than by the touch frame around it. Do not "simplify" this back to
 * `Button` — see the JellyfinButtons header for the full story.
 */
@Composable
private fun SheetChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showLabel: Boolean = true,
) {
    if (!showLabel) {
        Box(modifier = Modifier.size(Dimens.MinTouchTarget), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(CHIP_HEIGHT)
                        .glassSurface(CircleShape, tint = VIDEO_GLASS_FILL)
                        .clickable(role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(CHIP_ICON),
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .height(CHIP_HEIGHT)
                    .glassSurface(CircleShape, tint = VIDEO_GLASS_FILL)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = CHIP_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(CHIP_ICON))
            Text(
                text = label,
                style = CHIP_LABEL_STYLE,
                color = Color.White,
                modifier = Modifier.padding(start = Dimens.SpaceExtraSmall),
            )
        }
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

private val SCRIM = Color.Black.copy(alpha = 0.35f)

/**
 * The flat fill of every glass control drawn over the film — the same translucent dark the
 * `WaitingForGroupOverlay` panel uses, for the reason spelled out in this file's header: there is
 * no Haze backdrop over a `SurfaceView`, so the fill *is* the surface. Shared with `PlayerScreen`
 * (the skip-segment pill floats over raw video with no [SCRIM] behind it).
 */
internal val VIDEO_GLASS_FILL = Color.Black.copy(alpha = 0.6f)

// --- Top bar -----------------------------------------------------------------------------------

/** Back and Cast: the 44dp glass circle, not the 36dp chrome one — these are thumbed over a film. */
private val CHROME_BUTTON = Dimens.PillHeight

private val TITLE_STYLE = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)

private val SUBTITLE_STYLE = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)

private const val SUBTITLE_ALPHA = 0.7f

/**
 * The tag's text colour, `#7FD8F5`.
 *
 * A lightened `primary` rather than `primary` itself: `#00A4DC` at 10sp on an 18%-tinted fill is
 * under the contrast an overlay over moving video needs, and the mocks specify this exact value.
 */
private val TAG_TEXT = Color(0xFF7FD8F5)

private const val TAG_FILL_ALPHA = 0.18f

private const val TAG_BORDER_ALPHA = 0.4f

private val TAG_BORDER_WIDTH = 1.dp

private val TAG_PADDING_HORIZONTAL = 10.dp

private val TAG_PADDING_VERTICAL = 4.dp

private val TAG_STYLE =
    TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 0.08.em,
    )

// --- Transport ---------------------------------------------------------------------------------

private val SEEK_BUTTON = 52.dp

private val SEEK_ICON = 26.dp

private val PLAY_BUTTON = 68.dp

private val PLAY_ICON = 30.dp

/** The glyph on the white disc: the app background colour, as everywhere else in the refresh. */
private val PLAY_GLYPH = Color(0xFF101010)

// --- Bottom bar --------------------------------------------------------------------------------

private val CLOCK_STYLE =
    TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
        fontFeatureSettings = "tnum",
    )

private const val CLOCK_DIM_ALPHA = 0.6f

private val TRACK_HEIGHT = 5.dp

private val TRACK_COLOR = Color.White.copy(alpha = 0.2f)

private val BUFFERED_COLOR = Color.White.copy(alpha = 0.32f)

private val THUMB_SIZE = 14.dp

private val THUMB_SHADOW = 4.dp

private val THUMB_SHADOW_COLOR = Color.Black.copy(alpha = 0.45f)

private val CHIP_HEIGHT = 32.dp

private val CHIP_ICON = 15.dp

private val CHIP_PADDING = 12.dp

private val CHIP_LABEL_STYLE = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)

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
@Preview(name = "Player controls · phone landscape", widthDp = 800, heightDp = 360)
@Composable
private fun PlayerControlsPhoneLandscapePreview() {
    ControlsPreview()
}

/** The same controls on the tablet, where there is room for the pickers' words. */
@Preview(name = "Player controls · tablet landscape", widthDp = 1138, heightDp = 640)
@Composable
private fun PlayerControlsTabletLandscapePreview() {
    ControlsPreview()
}

/**
 * The whole control surface, drawn without [PlayerControls] itself.
 *
 * The state is in a group on purpose, and not only for the five-picker worst case: the cast button
 * is composed out while in one, and it is an `AndroidView` behind a `hiltViewModel()` that no
 * preview can build.
 */
@Composable
private fun ControlsPreview() {
    val state =
        PlayerUiState(
            isLoading = false,
            title = "The Original${PLAYER_LABEL_SEPARATOR}Star Trek · S1 E10",
            durationMs = 45.minutes.inWholeMilliseconds,
            playMethod = PlayMethod.TRANSCODE,
            videoHeight = 1080,
            audioTracks =
                listOf(
                    previewTrack(index = 1, label = "English"),
                    previewTrack(index = 2, label = "Français"),
                ),
            subtitleTracks = listOf(previewTrack(index = 3, label = "English (SDH)")),
            // In a group with a queue: audio, subtitles, group, queue and quality — the five-picker
            // worst case the narrow bar has to survive.
            syncPlay = PlayerSyncPlayState(inGroup = true, groupName = "Film night", hasQueue = true),
        )
    val position =
        MutableStateFlow(
            PlaybackPosition(
                positionMs = 12.minutes.inWholeMilliseconds,
                bufferedMs = 19.minutes.inWholeMilliseconds,
            ),
        )

    JellyfinTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            TopBar(state = state, onBack = {}, modifier = Modifier.align(Alignment.TopStart))
            TransportRow(
                isPlaying = true,
                onPlayPause = {},
                onSeekBy = {},
                modifier = Modifier.align(Alignment.Center),
            )
            BottomBar(
                state = state,
                position = position,
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
