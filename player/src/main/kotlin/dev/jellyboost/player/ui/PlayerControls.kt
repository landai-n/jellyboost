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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The transport controls drawn over the video.
 *
 * Scrubbing is local while the finger is down and seeks on release, so a drag across a two-hour film
 * does not fire hundreds of seeks at a transcoding server.
 *
 * **This composable draws and nothing else.** Anything that must outlive the four-second auto-hide
 * belongs to `PlayerScreen`: a `remember`ed sheet here would sit inside the very
 * `AnimatedVisibility(controlsVisible)` the auto-hide drives and be disposed mid-selection.
 *
 * The glass here is *flat* dark glass ([VIDEO_GLASS_FILL]), never a Haze blur: the video is a
 * `SurfaceView` composited by the system, so its pixels never reach the recorded backdrop layer and a
 * blur over it samples nothing — it rendered as an opaque `#101010` disc while still paying a
 * per-frame blur pass. `JellyfinNavHost` nulls `LocalHazeState` for the player subtree.
 */
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    position: StateFlow<PlaybackPosition>,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(SCRIM)) {
        TopBar(state = state, onBack = actions.onBack, modifier = Modifier.align(Alignment.TopStart))

        TransportRow(
            isPlaying = state.showsPlaying,
            // A receiver's buffering is unknowable from here, and the group-waiting overlay already
            // names that pause better.
            isBuffering =
                state.isBuffering &&
                    !state.syncPlay.isWaitingForGroup &&
                    !state.cast.isCasting,
            onPlayPause = actions.onPlayPause,
            onSeekBy = actions.onSeekBy,
            modifier = Modifier.align(Alignment.Center),
        )

        BottomBar(
            state = state,
            position = position,
            actions = actions,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
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
                .windowInsetsPadding(playerBarInsets())
                .padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.player_back),
            onClick = onBack,
            size = CHROME_BUTTON,
            // Full white, not the chrome default of white@80%: read against a moving image.
            tint = Color.White,
            surfaceTint = VIDEO_GLASS_FILL,
        )
        TitleStack(label = state.title, modifier = Modifier.weight(1f))
        // Only when it is not 1×: a badge that is always there stops being information.
        if (!state.speed.isNormal) {
            TagPill(text = state.speed.label)
        }
        state.playMethod?.let { method ->
            TagPill(text = playbackMethodTag(method = method, videoHeight = state.videoHeight))
        }
        // Casting and SyncPlay are mutually exclusive: composed out rather than drawn and refused.
        if (!state.syncPlay.inGroup) {
            CastRouteButton(
                // The frame this bar's `GlassIconButton`s reserve, with the circle drawn at the
                // bar's own button size inside it — see `JellyfinButtons.kt`.
                modifier = Modifier.size(Dimens.MinTouchTarget),
                glassContainer = true,
                size = CHROME_BUTTON,
                surfaceTint = VIDEO_GLASS_FILL,
            )
        }
    }
}

/**
 * The second line is *derived*: `PlayerViewModel.loadTitleAndArtwork` joins title and episode line
 * with [PLAYER_LABEL_SEPARATOR], and this splits them back apart — no second field on the state.
 */
@Composable
private fun TitleStack(
    label: String,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = remember(label) { label.asTitleAndSubtitle() }

    // One node, not two: as separate stops the second line reads as an orphan.
    Column(modifier = modifier.semantics(mergeDescendants = true) { }) {
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
 * Primary-tinted rather than glass: glass is reserved for surfaces the user can press.
 *
 * The uppercasing stops here — some speech engines read an uppercased *string* letter by letter, so
 * the pill draws the shouted form and describes the sentence-case one.
 */
@Composable
private fun TagPill(text: String) {
    val primary = MaterialTheme.colorScheme.primary
    // From the configuration, not `Locale.getDefault()`: the latter is read once and never observed,
    // so a language switch would keep casing by the old locale (lint: `NonObservableLocale`).
    val locale = LocalConfiguration.current.locales[0]

    Text(
        text = text.uppercase(locale),
        style = TAG_STYLE,
        color = TAG_TEXT,
        maxLines = 1,
        modifier =
            Modifier
                .semantics { contentDescription = text }
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
 * [PlayerUiState.videoHeight] is what the decoder reports — for a transcode, what the server chose
 * to send. It is `0` before the first frame and while a receiver has the film, and the tag is then
 * the method alone rather than a made-up resolution.
 */
@Composable
private fun playbackMethodTag(
    method: PlayMethod,
    videoHeight: Int,
): String {
    val label = stringResource(method.labelRes())
    return if (videoHeight > 0) {
        stringResource(R.string.player_method_tag_height, label, videoHeight)
    } else {
        label
    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
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
        // A Play triangle while the stream is opening invites a tap that *cancels* the start.
        if (isBuffering) BufferingDisc() else PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
        SeekButton(
            icon = Icons.Filled.Forward30,
            contentDescription = stringResource(R.string.player_forward),
            onClick = { onSeekBy(SKIP_FORWARD_MS) },
        )
    }
}

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

/** The same white disc, non-interactive for exactly the window a tap would cancel the start. */
@Composable
private fun BufferingDisc() {
    val label = stringResource(R.string.player_buffering)
    Box(
        modifier =
            Modifier
                .size(PLAY_BUTTON)
                .background(OVER_MEDIA_DISC, CircleShape)
                .semantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = PLAY_GLYPH,
            modifier = Modifier.size(PLAY_ICON),
        )
    }
}

/** The one solid surface on the screen: white fill, `#101010` glyph. */
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
                containerColor = OVER_MEDIA_DISC,
                contentColor = PLAY_GLYPH,
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription =
                stringResource(if (isPlaying) R.string.player_pause else CoreUiR.string.action_play),
            modifier = Modifier.size(PLAY_ICON),
        )
    }
}

@Composable
private fun BottomBar(
    state: PlayerUiState,
    position: StateFlow<PlaybackPosition>,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = MAX_BAR_WIDTH)
                .fillMaxWidth()
                .windowInsetsPadding(playerBarInsets())
                .padding(Dimens.SpaceLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        Scrubber(
            position = position,
            durationMs = state.durationMs,
            trickplay = state.trickplay,
            onSeekTo = actions.onSeekTo,
        )

        // One subcomposition for the whole row: "is there room for words?" is about its total width,
        // and every button has to answer it the same way.
        val chips = visibleSheetChips(state)
        val values = sheetChipValues(state)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val showLabels = showSheetButtonLabels(maxWidth, LocalDensity.current.fontScale)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // Each SheetChip carries an invisible 48dp touch frame around its 32dp visual, so
                // spacing here stacks on the frames' own gap: 0dp yields 16dp between circles.
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Clock(position = position, durationMs = state.durationMs, modifier = Modifier.weight(1f))

                // Keyed by identity, not position: the list changes shape at runtime (a group inserts
                // two chips mid-row), and unkeyed positional slots would hand the third chip's
                // remembered state to a different picker.
                chips.forEach { chip ->
                    key(chip.id) {
                        SheetChip(
                            label = chip.id.label(state),
                            onClick = { actions.onOpenPanel(chip.id.panel) },
                            icon = chip.id.icon,
                            showLabel = showLabels,
                            value = chip.id.value(values),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The system bars **and** the display cutout, as a union rather than a sum. The player hides the
 * bars, so that inset is usually zero and the cutout is what remains; the bars stay in the union
 * because a transient swipe floats them back over this layout. `union` takes the larger per edge —
 * chaining `.systemBarsPadding().displayCutoutPadding()` would inset by both.
 */
@Composable
private fun playerBarInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * [LABELLED_BUTTONS_MIN_WIDTH] is a measured number, not an arithmetic one: a device-width sweep put
 * five labelled pickers plus the clock at near-zero slack below it. It is verified for five chips
 * and unverified for six ([MAX_SHEET_CHIPS], pinned by `SheetChipSpecTest`) — do not move it without
 * re-measuring.
 *
 * Scaled by [fontScale] because the sweep measured the default text size and every label is twice as
 * wide at 2×, but never lowered below 1×: the sweep's number is a floor, not a ratio.
 */
internal fun showSheetButtonLabels(
    maxWidth: Dp,
    fontScale: Float,
): Boolean = maxWidth >= LABELLED_BUTTONS_MIN_WIDTH * fontScale.coerceAtLeast(1f)

/**
 * The *first* separator is the one `PlayerViewModel.loadTitleAndArtwork`'s join inserted; the
 * subtitle's own middots survive intact in the second line.
 *
 * @return the title, and the second line or `null` for a film or a blank subtitle.
 */
internal fun String.asTitleAndSubtitle(): Pair<String, String?> {
    val separator = indexOf(PLAYER_LABEL_SEPARATOR)
    if (separator < 0) return this to null

    val subtitle = substring(separator + PLAYER_LABEL_SEPARATOR.length)
    return substring(0, separator) to subtitle.takeIf { it.isNotBlank() }
}

/**
 * Its own composable so collecting the position confines the twice-a-second recomposition to two
 * `Text`s rather than the picker row it sits in. `remember`ed against the *whole second*, so the
 * format only re-runs when the displayed number changes. Tabular figures ([CLOCK_STYLE]) stop the
 * readout shifting sideways as digits change.
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
    val spoken = spokenPosition(positionMs = elapsedSeconds * MILLIS_PER_SECOND, durationMs = durationMs)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // One node: "12:34" reaches a speech engine as "twelve thirty-four", a time of day.
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
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
 * The preview is drawn at a negative offset, *outside* this composable's bounds: reserving space for
 * it would push the whole control bar up permanently. Its x is clamped to the bar so a scrub to
 * either end cannot leave it hanging off screen.
 *
 * A drag fires this at up to 90 Hz, so the preview's clock text is `remember`ed against the whole
 * second as in [Clock], and the preview uses the *lambda* `offset {}` overload — read during
 * placement, not composition, so moving it is layout's job rather than a recomposition.
 *
 * The M3 `Slider` keeps its drag, press-to-seek, a11y actions and RTL mapping; only `track` and
 * `thumb` are supplied, because no `SliderColors` can express a buffered segment. Both slots read
 * the `fraction` held here rather than the `SliderState`, since during a drag that local value *is*
 * what is on screen.
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
                                // The label is text and grows with the system scale: at 2× a fixed
                                // 18dp allowance puts the preview on top of its own caption.
                                y =
                                    -(
                                        TRICKPLAY_PREVIEW_HEIGHT + PREVIEW_GAP +
                                            PREVIEW_LABEL_HEIGHT * fontScale
                                    ).roundToPx(),
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
            modifier = scrubberSemantics(positionMs = current.positionMs, durationMs = durationMs, onSeekTo = onSeekTo),
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
 * The M3 `Slider` reads its position as a percentage, and one TalkBack adjust of a `0f..1f` slider
 * with no steps is about six minutes of a feature film — hence a `stateDescription` in *time* and
 * two custom actions carrying the transport's own −10s/+30s.
 *
 * Keyed to the whole second, not the twice-a-second tick, so the list is not rebuilt for a position
 * that reads the same.
 */
@Composable
private fun scrubberSemantics(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
): Modifier {
    val seekLabel = stringResource(R.string.player_seek)
    val backLabel = stringResource(R.string.player_rewind)
    val forwardLabel = stringResource(R.string.player_forward)
    val second = positionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val spoken = spokenPosition(positionMs = second * MILLIS_PER_SECOND, durationMs = durationMs)

    val actions =
        remember(second, durationMs, backLabel, forwardLabel, onSeekTo) {
            val from = second * MILLIS_PER_SECOND
            listOf(
                CustomAccessibilityAction(backLabel) {
                    onSeekTo(seekTargetMs(from, -SKIP_BACK_MS, durationMs))
                    true
                },
                CustomAccessibilityAction(forwardLabel) {
                    onSeekTo(seekTargetMs(from, SKIP_FORWARD_MS, durationMs))
                    true
                },
            )
        }

    return Modifier.semantics {
        contentDescription = seekLabel
        stateDescription = spoken
        customActions = actions
    }
}

/** A duration of `0` means "not known yet": there is no upper bound to clamp to. */
internal fun seekTargetMs(
    positionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long = (positionMs + deltaMs).coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)

/** "12 minutes 34 seconds of 45 minutes" — the clock and the seek bar say the same sentence. */
@Composable
private fun spokenPosition(
    positionMs: Long,
    durationMs: Long,
): String =
    stringResource(
        R.string.player_position_of_duration,
        spokenTime(positionMs),
        spokenTime(durationMs),
    )

/** In words a speech engine reads as a length rather than as a time of day. */
@Composable
private fun spokenTime(millis: Long): String {
    val parts = millis.asSpokenTimeParts()
    val words = ArrayList<String>(parts.size)
    for (part in parts) {
        val value = part.value.toInt()
        words += pluralStringResource(part.unit.pluralRes(), value, value)
    }
    return words.joinToString(" ")
}

internal enum class SpokenTimeUnit {
    HOURS,
    MINUTES,
    SECONDS,
}

internal data class SpokenTimePart(
    val unit: SpokenTimeUnit,
    val value: Long,
)

/**
 * The rule: **at most two units, and never a zero that carries no information** — except a position
 * under a minute, which speaks "0 seconds" so a film that has not begun still says where it is.
 */
internal fun Long.asSpokenTimeParts(): List<SpokenTimePart> {
    val duration = coerceAtLeast(0L).milliseconds
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % MINUTES_PER_HOUR
    val seconds = duration.inWholeSeconds % SECONDS_PER_MINUTE

    return when {
        hours > 0L ->
            listOf(
                SpokenTimePart(SpokenTimeUnit.HOURS, hours),
                SpokenTimePart(SpokenTimeUnit.MINUTES, minutes),
            )

        minutes > 0L && seconds > 0L ->
            listOf(
                SpokenTimePart(SpokenTimeUnit.MINUTES, minutes),
                SpokenTimePart(SpokenTimeUnit.SECONDS, seconds),
            )

        minutes > 0L -> listOf(SpokenTimePart(SpokenTimeUnit.MINUTES, minutes))

        else -> listOf(SpokenTimePart(SpokenTimeUnit.SECONDS, seconds))
    }
}

private fun SpokenTimeUnit.pluralRes(): Int =
    when (this) {
        SpokenTimeUnit.HOURS -> R.plurals.player_spoken_hours
        SpokenTimeUnit.MINUTES -> R.plurals.player_spoken_minutes
        SpokenTimeUnit.SECONDS -> R.plurals.player_spoken_seconds
    }

/**
 * The buffered band is measured from zero, not from the play head: the gap between the two edges is
 * the answer to "why did it stall" on an HLS transcode.
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
 * The shadow is much smaller than `JellyfinElevation`'s card shadow: at 14dp that elevation draws a
 * grey smudge rather than a lift.
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
 * Both forms announce the same thing: labelled, the [Text] names the button and the icon's
 * description stays null; icon-only, the description moves onto the icon.
 *
 * **Do not "simplify" this back to M3 `Button`.** `Button` delegates to `Surface`, which inserts
 * `Modifier.minimumInteractiveComponentSize()` *inside* the caller's chain, so `.size(32.dp)
 * .glassSurface(…)` clips and outlines a 48dp node and adjacent chips overlap. The outer `Box`
 * reserves [Dimens.MinTouchTarget]; the inner draws the glass at [CHIP_HEIGHT], with the click
 * target inside its clip so the ripple is bounded by the visible shape.
 *
 * @param modifier applied to the invisible touch frame, this composable's outermost node.
 */
@Composable
private fun SheetChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    value: String? = null,
) {
    // "Audio, English" rather than "Audio": a picker says what it is set to without being opened.
    val state = if (value == null) Modifier else Modifier.semantics { stateDescription = value }

    if (!showLabel) {
        Box(modifier = modifier.size(Dimens.MinTouchTarget), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(CHIP_HEIGHT)
                        .glassSurface(CircleShape, tint = VIDEO_GLASS_FILL)
                        .clickable(role = Role.Button, onClick = onClick)
                        .then(state),
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
        modifier = modifier.heightIn(min = Dimens.MinTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    // A *minimum*, not a fixed height: at accessibility font scales the 12sp label is
                    // taller than the 32dp capsule, and a hard `height` clipped the word.
                    .heightIn(min = CHIP_HEIGHT)
                    .glassSurface(CircleShape, tint = VIDEO_GLASS_FILL)
                    .clickable(role = Role.Button, onClick = onClick)
                    .then(state)
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

/**
 * Shared by the seek circles, the keyboard arrows (`PlayerScreen`) and the seek bar's custom
 * actions, so the button and the action that say the same words cannot drift apart.
 */
internal const val SKIP_BACK_MS = 10_000L
internal const val SKIP_FORWARD_MS = 30_000L

/**
 * Sized by contrast: the worst case is a *white* frame, which black@62% composites to rgb(97) —
 * full-white text at 6.20:1, and the number every dimmed token below is sized against. Black@35%
 * left the title at 2.44:1, under WCAG 1.4.3.
 */
private val SCRIM = Color.Black.copy(alpha = 0.62f)

/**
 * There is no Haze backdrop over a `SurfaceView` (see the file header), so this fill *is* the
 * surface of every glass control over the film. Shared with `PlayerScreen`.
 */
internal val VIDEO_GLASS_FILL = Color.Black.copy(alpha = 0.6f)

// --- Top bar -----------------------------------------------------------------------------------

/** Back and Cast: the 44dp glass circle, not the 36dp chrome one — these are thumbed over a film. */
private val CHROME_BUTTON = Dimens.PillHeight

private val TITLE_STYLE = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)

private val SUBTITLE_STYLE = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)

/**
 * The floor is contrast, not hierarchy: over [SCRIM]'s worst case of rgb(97), 12sp white needs
 * α ≥ 0.775 for 4.5:1. 0.85 gives 5.03:1.
 */
private const val SUBTITLE_ALPHA = 0.85f

/**
 * A lightened `primary`: `#00A4DC` at 10sp on an 18%-tinted fill is under the contrast an overlay
 * over moving video needs. The mocks specify this exact value.
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

/**
 * The one pill in the app that does not follow the colour scheme. It sits on the video frame, which
 * is letterboxed black in both themes, so the disc stays white and its glyph stays `#101010`
 * whatever Settings is set to — the same reason the scrims above are literal black.
 */
private val OVER_MEDIA_DISC = Color.White

private val PLAY_GLYPH = Color(0xFF101010)

// --- Bottom bar --------------------------------------------------------------------------------

private val CLOCK_STYLE =
    TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
        fontFeatureSettings = "tnum",
    )

/** Same floor as [SUBTITLE_ALPHA]: α ≥ 0.775 for 4.5:1 over [SCRIM]'s worst case; 0.85 is 5.03:1. */
private const val CLOCK_DIM_ALPHA = 0.85f

private val TRACK_HEIGHT = 5.dp

/**
 * WCAG 1.4.11 asks 3:1 for a component's own boundaries: over [SCRIM]'s rgb(97), white needs
 * α ≥ 0.527. The track at 0.55 is 3.12:1 and the buffered band at 0.80 is 4.67:1, 1.50:1 apart so
 * the buffer band stays legible against the track.
 */
private val TRACK_COLOR = Color.White.copy(alpha = 0.55f)

private val BUFFERED_COLOR = Color.White.copy(alpha = 0.8f)

private val THUMB_SIZE = 14.dp

private val THUMB_SHADOW = 4.dp

private val THUMB_SHADOW_COLOR = Color.Black.copy(alpha = 0.45f)

private val CHIP_HEIGHT = 32.dp

private val CHIP_ICON = 15.dp

private val CHIP_PADDING = 12.dp

private val CHIP_LABEL_STYLE = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)

private val MAX_BAR_WIDTH = 1000.dp

/** Measured; see [showSheetButtonLabels] before changing it. */
private val LABELLED_BUTTONS_MIN_WIDTH = 840.dp

private val PREVIEW_GAP = 8.dp
private val PREVIEW_LABEL_HEIGHT = 18.dp

@Preview(name = "Player controls · phone landscape", widthDp = 800, heightDp = 360)
@Composable
private fun PlayerControlsPhoneLandscapePreview() {
    ControlsPreview()
}

@Preview(name = "Player controls · tablet landscape", widthDp = 1138, heightDp = 640)
@Composable
private fun PlayerControlsTabletLandscapePreview() {
    ControlsPreview()
}

/**
 * In a group on purpose: the cast button is composed out there, and it is an `AndroidView` behind a
 * `hiltViewModel()` no preview can build.
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
            // In a group with a queue: audio, subtitles, group, queue, display and quality — the
            // [MAX_SHEET_CHIPS] worst case the narrow bar has to survive.
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
                isBuffering = false,
                onPlayPause = {},
                onSeekBy = {},
                modifier = Modifier.align(Alignment.Center),
            )
            BottomBar(
                state = state,
                position = position,
                actions = previewActions(),
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
        onPlayNext = {},
        onDismissUpNext = {},
        onBack = {},
    )
