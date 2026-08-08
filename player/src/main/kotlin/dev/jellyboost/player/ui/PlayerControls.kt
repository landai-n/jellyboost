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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
 * Scrubbing is deliberately local while the finger is down — the slider follows the touch and only
 * seeks on release, so a drag across a two-hour film does not fire hundreds of seeks at a
 * transcoding server. Since M9 that same drag also drives the trickplay preview, which is the whole
 * reason the scrub position is state rather than a callback.
 *
 * The bottom bar is width-capped and centred: on a 2560 px tablet a seek bar stretched edge to edge
 * puts the time readout and the pickers a hand-span apart from each other.
 *
 * **This composable draws and nothing else.** It holds no picker state: a chip tap goes straight to
 * `PlayerActions.onOpenPanel`, and `PlayerScreen` hosts every panel above the auto-hide. It used to
 * `remember` an open sheet here, inside the very `AnimatedVisibility(controlsVisible)` the auto-hide
 * drives, so the picker was disposed mid-selection a second or two after it was opened (audit UI-1).
 * Anything on this screen that must outlive four seconds belongs to the screen, not to the bar.
 *
 * ### The 2026 refresh
 * Everything here is glass over the film — circles for the seek buttons and the chrome, a pill for
 * each picker — with exactly one solid surface on the screen: the white play/pause disc. That is the
 * refresh's rule for primary actions (DECISIONS.md 2026-08-01, "primary action buttons are white"),
 * and over a moving image it is also the only thing that stays findable at a glance. No control was
 * added, removed or rewired in the restyle; the seek amounts, the picker set and the label threshold
 * are the M9–M12 ones.
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

    // One node, not two (audit A11Y-P-14): "The Original" and "Star Trek · S1 E10" are one answer to
    // "what am I watching", and as separate stops the second reads as an orphan.
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
 * A small tinted tag — the playback method, and the playback rate when it is not 1×.
 *
 * Primary-tinted rather than glass: these two say something *about the stream* rather than offering
 * an action, and the refresh reserves glass for surfaces the user can press.
 *
 * The uppercasing is this composable's, and stops here (audit A11Y-P-15): an uppercased *string*
 * reaches text-to-speech as one, and "TRANSCODING 1080P" is read out letter by letter by some
 * engines. The pill draws the shouted form and describes the sentence-case one.
 */
@Composable
private fun TagPill(text: String) {
    val primary = MaterialTheme.colorScheme.primary
    // From the configuration rather than `Locale.getDefault()`: the latter is read once and never
    // observed, so a pill composed before the user switches the app's language would keep casing
    // its word by the old locale's rules — which for Turkish is the difference between "TITLE" and
    // "TİTLE". Lint calls this `NonObservableLocale`; it is an error in this project.
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
 * The method tag's words: "Transcoding 1080p", or just the method — drawn uppercased by [TagPill].
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
    return if (videoHeight > 0) {
        stringResource(R.string.player_method_tag_height, label, videoHeight)
    } else {
        label
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

        // One subcomposition for the whole picker row, not one per button: the question — is there
        // room for words next to the icons? — is about the row's total width, and every button in it
        // has to answer it the same way.
        val chips = visibleSheetChips(state)
        val values = sheetChipValues(state)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val showLabels = showSheetButtonLabels(maxWidth, LocalDensity.current.fontScale)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // Each SheetChip already carries an invisible Dimens.MinTouchTarget (48dp) frame
                // around its 32dp visual (see that composable's KDoc), so any arrangement spacing
                // here stacks on top of the frames' own gap — 0dp yields 16dp between circles.
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Clock(position = position, durationMs = state.durationMs, modifier = Modifier.weight(1f))

                // Keyed by identity, not by position (audit UI-13): this list changes shape at
                // runtime — joining a group inserts two chips in the middle of it, a receiver
                // connecting removes another — and an unkeyed `forEach` gives Compose positional
                // slots, so the chip that *was* third keeps the third slot's remembered state while
                // drawing a different picker's icon. `key` makes the identity the chip's own.
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
 * What the top and bottom bars hold themselves off: the system bars **and** the display cutout, as a
 * union rather than as a sum (audit UI-16).
 *
 * `systemBarsPadding()` alone was the wrong question in this window. The player hides the system
 * bars, so on most devices that inset resolves to zero — and a bar drawn at the very top of a
 * landscape screen then runs straight under the notch, where a phone puts the back button and the
 * title. The cutout is the inset that is still there when the bars are not.
 *
 * The bars stay in the union rather than being replaced by the cutout, because they come *back*:
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` means a swipe from the edge floats them over this very
 * layout, and a three-button navigation bar on an older device is never hidden at all.
 * `WindowInsets.union` takes the larger of the two per edge — the notch and the status bar occupy
 * the same edge, and adding `.systemBarsPadding().displayCutoutPadding()` would inset by both.
 */
@Composable
private fun playerBarInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Whether the bottom bar's pickers have room to say what they are, given [maxWidth] — the width the
 * picker row itself was handed, inside the bar's padding — and [fontScale], the system text scale
 * those words are drawn at.
 *
 * A device sweep put the fullest bar it knew of — five pickers plus the clock — at near-zero slack
 * once its row drops much below [LABELLED_BUTTONS_MIN_WIDTH]: the clock is what gives first, and then
 * the last picker clips off the end. Phone landscape (roughly 640–800 dp of viewport) and tablet
 * portrait (711 dp) both land there, so both go icon-only rather than squeezing the readout out;
 * tablet landscape, where the bar is capped at [MAX_BAR_WIDTH], stays labelled exactly as before.
 *
 * **The fullest bar is now [MAX_SHEET_CHIPS] pickers, not five**, and that is a fact rather than a
 * guess since [sheetChipSpecs] made the rules enumerable: the accessibility audit's display picker
 * (CR-8) landed after the sweep and pushed the in-a-group-with-a-queue case from five to six. The dp
 * number below is left exactly where the sweep put it — moving a measured constant on the strength of
 * arithmetic would be inventing a measurement — so the honest statement of where this stands is that
 * the threshold is known to be right for five labelled chips and unverified for six, on a viewport
 * between 840 dp and [MAX_BAR_WIDTH], in a SyncPlay group with a queue. `SheetChipSpecTest` pins the
 * count so the next picker cannot widen the gap silently.
 *
 * The 2026 refresh's chips are *narrower* than the text buttons that sweep measured, so the
 * threshold is now conservative where it used to be tight. The dp number is deliberately unchanged:
 * it is the same devices either side of it, and moving a pinned number for extra slack buys nothing.
 *
 * What *has* changed is that the words are no longer assumed to be 12sp (audit A11Y-P-10). A width
 * sweep measured at the default text size says nothing about the same row at 1.5× or 2×, where every
 * label is half again or twice as wide and the last picker clips off the end — the very failure the
 * threshold exists to prevent. Scaling the threshold by [fontScale] asks the honest question: is
 * there room for these words *at the size they will actually be drawn*. Below 1× the threshold is
 * not lowered (`coerceAtLeast(1f)`): a small-text user gains nothing from labels that were already
 * judged too tight, and the sweep's number is a floor rather than a ratio.
 *
 * Pure and `internal` so the threshold is a unit test rather than a screenshot.
 */
internal fun showSheetButtonLabels(
    maxWidth: Dp,
    fontScale: Float,
): Boolean = maxWidth >= LABELLED_BUTTONS_MIN_WIDTH * fontScale.coerceAtLeast(1f)

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
    val spoken = spokenPosition(positionMs = elapsedSeconds * MILLIS_PER_SECOND, durationMs = durationMs)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // One node saying "12 minutes 34 seconds of 45 minutes" (audit A11Y-P-13). Two nodes reading
        // "12:34" and "/ 45:00" is two stops for one fact, and "12:34" reaches a speech engine as
        // "twelve thirty-four" — a time of day, not a position in a film.
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
                                // The label's height is text, so it grows with the system text scale
                                // (audit A11Y-P-11): at 2× a fixed 18dp allowance left the preview
                                // sitting half on top of the very clock it is captioned with.
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
 * What the seek bar is, where it is, and the two moves that mean something in a film.
 *
 * The M3 `Slider` underneath already exposes a range and commits a seek on `setProgress`, which is
 * the hard half and was done right. What it had was no name at all and a percentage for a position
 * ("34 percent"), and one TalkBack adjust of a `0f..1f` slider with no steps is about six minutes on
 * a feature film (audit A11Y-P-04/05).
 *
 * So: a name, a `stateDescription` in *time* rather than percent, and two custom actions carrying
 * the transport's own −10s/+30s — the same numbers, the same words, as the buttons above the bar.
 * The fractional adjust stays exactly as it was for anyone who wants a coarse jump.
 *
 * The actions are keyed to the whole second the position is in, not to the raw twice-a-second tick:
 * the list would otherwise be rebuilt for a position that reads the same, which is the same argument
 * [Clock] makes about its own text.
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

/**
 * Where a relative seek lands, clamped to the item.
 *
 * Pure, because "skip back 10 seconds" from 4 seconds in must be 0 rather than −6, and "skip forward
 * 30" near the end must be the end rather than past it — arithmetic worth a test rather than a
 * playthrough. A duration of `0` means "not known yet", where there is no upper bound to clamp to.
 */
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

/** One duration, in words a speech engine reads as a length rather than as a time of day. */
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
 * Which units a duration is worth speaking, and how many of each.
 *
 * The rule, and the reason it is pure and tested: **only two units, and never a zero that carries no
 * information.** "1 hour 3 minutes" is a position in a film; "1 hour 3 minutes 12 seconds" is a
 * stopwatch reading nobody asked for, and it is spoken every time the clock is traversed. Below an
 * hour the seconds matter (they are the difference between the start of a scene and the middle of
 * one), so minutes and seconds are both spoken — except an exact number of minutes, which drops the
 * "0 seconds". A position under a minute is spoken in seconds alone, including "0 seconds" at the
 * very start, because a film that has not begun still has to say where it is.
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
 *
 * @param modifier applied to the invisible touch frame, which is this composable's outermost node —
 *   so a caller positions the 48dp frame and the capsule stays centred in it (audit UI-18).
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
    // The chip's *state*, in the settings rows' pattern: "Audio, English" rather than "Audio", so a
    // picker says what it is set to without being opened (audit A11Y-P-09). `null` where there is no
    // current value to speak — the label is then the whole truth.
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
                    // A *minimum*, not a fixed height: [CHIP_HEIGHT] is 32dp around a 12sp label,
                    // which at accessibility font scales is taller than the capsule — a hard
                    // `height` clipped the very word the labelled form exists to show. The chip
                    // floats inside a 48dp touch frame, so growing costs the row nothing.
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
 * How far the transport's two seek circles move, and — since the accessibility pass — how far the
 * keyboard's arrow keys (`PlayerScreen`) and the seek bar's custom actions move too. One pair of
 * numbers for all three, so the button that says "skip forward 30 seconds" and the action that says
 * the same words cannot drift apart.
 */
internal const val SKIP_BACK_MS = 10_000L
internal const val SKIP_FORWARD_MS = 30_000L

/**
 * The flat wash the whole control layer sits on.
 *
 * Sized by contrast, not by taste (accessibility audit 2026-08-05, CR-5). The worst case a scrim
 * over video has to survive is a *white* frame, and black@35% only pulls that down to rgb(166),
 * where the title read at 2.44:1 and the seek track at 1.23:1 — both under WCAG 1.4.3/1.4.11.
 * Black@62% composites the same frame to rgb(97), where full-white text is 6.20:1 and every dimmed
 * token below is sized against that one number. It is also the value the file's own
 * [VIDEO_GLASS_FILL] already used for exactly this reason, so the wash and the glass on it now
 * agree. A darker wash costs a little of the film while the controls are up; the controls are only
 * up for four seconds at a time.
 */
private val SCRIM = Color.Black.copy(alpha = 0.62f)

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

/**
 * How far the subtitle line is held off full white.
 *
 * The floor is contrast, not hierarchy: over [SCRIM]'s worst-case composite of rgb(97), white needs
 * α ≥ 0.775 to clear 4.5:1 for 12sp text. 0.85 gives 5.03:1 with a little margin, and still reads a
 * step behind the title (which is full white at 6.20:1). At the old 0.7-over-black@35% pairing this
 * line was 1.92:1.
 */
private const val SUBTITLE_ALPHA = 0.85f

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

/**
 * The clock's dim half — the total duration, against the full-white elapsed time.
 *
 * Same arithmetic as [SUBTITLE_ALPHA]: 12sp text needs 4.5:1, [SCRIM]'s worst case is rgb(97), so
 * white needs α ≥ 0.775; 0.85 gives 5.03:1. The old 0.6 was 1.77:1 over the old scrim. The dim/full
 * pairing survives because the *elapsed* half is full white — the difference is smaller than it was,
 * which is the price of a number a viewer can actually read.
 */
private const val CLOCK_DIM_ALPHA = 0.85f

private val TRACK_HEIGHT = 5.dp

/**
 * The scrubber's three bands are a UI component's own boundaries, so WCAG 1.4.11 asks 3:1 of each
 * against what it sits on — over [SCRIM]'s worst-case rgb(97) composite, white needs α ≥ 0.527.
 *
 * The unplayed track at 0.55 is 3.12:1 and the buffered band at 0.80 is 4.67:1; against the old
 * black@35% wash the same two were 1.23:1 and 1.38:1. The gap between them widened rather than
 * narrowed while both were raised — 1.50:1 band-to-band, up from 1.12:1 — so "how much is in the
 * buffer" is still legible at a glance, which is the whole reason the middle band exists.
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

/** Wide enough for a 21:9 film's controls, narrow enough to stay one glance on a tablet. */
private val MAX_BAR_WIDTH = 1000.dp

/**
 * Below this much room for the picker row, the pickers drop their words — see
 * [showSheetButtonLabels] for what the sweep measured, and [MAX_SHEET_CHIPS] for how many chips the
 * row it measured can actually hold.
 */
private val LABELLED_BUTTONS_MIN_WIDTH = 840.dp

private val PREVIEW_GAP = 8.dp
private val PREVIEW_LABEL_HEIGHT = 18.dp

/** The fullest bar there is — [MAX_SHEET_CHIPS] pickers plus the clock — at a phone's landscape width. */
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
 * The state is in a group on purpose, and not only for the [MAX_SHEET_CHIPS]-picker worst case: the
 * cast button is composed out while in one, and it is an `AndroidView` behind a `hiltViewModel()`
 * that no preview can build.
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
        onBack = {},
    )
