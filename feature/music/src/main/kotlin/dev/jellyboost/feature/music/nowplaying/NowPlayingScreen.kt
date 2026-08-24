package dev.jellyboost.feature.music.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.ArtistRef
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.feature.music.R

/**
 * Pops itself the moment the controller goes `Idle` — the queue emptied from the mini-player,
 * `stop()` elsewhere, a relaunch into an empty session — since nothing is left to show.
 */
@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel,
    onArtistClick: (JellyfinItem) -> Unit,
    onStartRadio: (JellyfinItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isIdle) {
        if (state.isIdle) onBack()
    }

    val actions =
        remember(viewModel) {
            NowPlayingActions(
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeekTo = viewModel::seekTo,
                onSetShuffle = viewModel::setShuffle,
                onCycleRepeat = viewModel::cycleRepeat,
                onToggleFavorite = viewModel::toggleFavorite,
                onJumpTo = viewModel::jumpTo,
                onRemove = viewModel::removeAt,
                onMoveItem = viewModel::moveItem,
                onStop = viewModel::stop,
            )
        }

    NowPlayingContent(
        state = state,
        actions = actions,
        onArtistClick = onArtistClick,
        onStartRadio = { state.track?.let(onStartRadio) },
        onBack = onBack,
        modifier = modifier,
    )
}

private data class NowPlayingActions(
    val onTogglePlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSetShuffle: (Boolean) -> Unit,
    val onCycleRepeat: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onJumpTo: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onMoveItem: (from: Int, to: Int) -> Unit,
    val onStop: () -> Unit,
)

@Composable
private fun NowPlayingContent(
    state: NowPlayingUiState,
    actions: NowPlayingActions,
    onArtistClick: (JellyfinItem) -> Unit,
    onStartRadio: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    // Compact only. The wide layout keeps its own `showLyrics` in `NowPlayingWideContent`, since it
    // shows both panes' chrome at once.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val track = state.track

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = isWideNowPlaying(maxWidth, maxHeight)

        if (track != null) {
            if (wide) {
                NowPlayingWideContent(
                    state = state,
                    track = track,
                    actions = actions,
                    onArtistClick = onArtistClick,
                    onStartRadio = onStartRadio,
                )
            } else {
                NowPlayingCompactContent(
                    state = state,
                    track = track,
                    actions = actions,
                    onArtistClick = onArtistClick,
                    onStartRadio = onStartRadio,
                    showLyrics = showLyrics && state.lyricsAvailable,
                )
            }
        }

        NowPlayingOverlayNav(
            onBack = onBack,
            onOpenQueue = if (wide) null else ({ showQueue = true }),
            onToggleLyrics = if (!wide && state.lyricsAvailable) ({ showLyrics = !showLyrics }) else null,
            lyricsShown = showLyrics,
            onStop = actions.onStop,
        )
    }

    if (showQueue) {
        QueueSheet(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onJumpTo = actions.onJumpTo,
            onRemove = actions.onRemove,
            onMoveUp = { index -> actions.onMoveItem(index, index - 1) },
            onMoveDown = { index -> actions.onMoveItem(index, index + 1) },
            onStop = actions.onStop,
            onDismiss = { showQueue = false },
        )
    }
}

/**
 * @param onOpenQueue `null` hides the button — the wide layout shows the queue inline.
 * @param onToggleLyrics `null` hides the button — no lyrics, or the wide layout's own tab.
 * @param onStop ends the session; the screen then pops itself off the idle state, so this never
 *   navigates. Unconditional, unlike its neighbours: the wide layout's inline queue has no header,
 *   so [QueueSheet]'s Stop is unreachable there and this is the only one.
 */
@Composable
private fun NowPlayingOverlayNav(
    onBack: () -> Unit,
    onOpenQueue: (() -> Unit)?,
    onToggleLyrics: (() -> Unit)?,
    lyricsShown: Boolean,
    onStop: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(Dimens.SpaceLarge),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.music_now_playing_back),
            onClick = onBack,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        Box(modifier = Modifier.weight(1f))
        if (onToggleLyrics != null) {
            GlassIconButton(
                icon = Icons.Filled.Lyrics,
                contentDescription =
                    stringResource(
                        if (lyricsShown) {
                            R.string.music_now_playing_lyrics_hide
                        } else {
                            R.string.music_now_playing_lyrics_show
                        },
                    ),
                onClick = onToggleLyrics,
                tint = if (lyricsShown) MaterialTheme.colorScheme.primary else GlassIconTint,
                surfaceTint = GlassDefaults.ChromeFill,
            )
        }
        if (onOpenQueue != null) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = stringResource(R.string.music_now_playing_open_queue),
                onClick = onOpenQueue,
                surfaceTint = GlassDefaults.ChromeFill,
            )
        }
        GlassIconButton(
            icon = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.music_now_playing_stop),
            onClick = onStop,
            surfaceTint = GlassDefaults.ChromeFill,
        )
    }
}

@Composable
private fun NowPlayingCompactContent(
    state: NowPlayingUiState,
    track: JellyfinItem,
    actions: NowPlayingActions,
    onArtistClick: (JellyfinItem) -> Unit,
    onStartRadio: () -> Unit,
    showLyrics: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(top = Dimens.MinTouchTarget + Dimens.SpaceMedium, bottom = Dimens.SpaceExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // `LyricsPane` must keep this square's exact footprint: the toggle swaps them in place.
        if (showLyrics && state.lyrics != null) {
            LyricsPane(
                lyrics = state.lyrics,
                activeLineIndex = state.activeLyricLineIndex,
                modifier = Modifier.fillMaxWidth().height(ArtworkSizeCompact),
            )
        } else {
            NowPlayingArtwork(track = track, size = ArtworkSizeCompact)
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceExtraLarge))

        NowPlayingTitleBlock(track = track, onArtistClick = onArtistClick, alignment = Alignment.CenterHorizontally)

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))

        NowPlayingSeekBar(positionMs = state.positionMs, durationMs = state.durationMs, onSeekTo = actions.onSeekTo)

        Spacer(modifier = Modifier.height(Dimens.SpaceSmall))

        NowPlayingTransportRow(state = state, actions = actions)

        Spacer(modifier = Modifier.height(Dimens.SpaceLarge))

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium)) {
            NowPlayingFavoriteButton(isFavorite = track.userData.isFavorite, onClick = actions.onToggleFavorite)
            NowPlayingStartRadioButton(onClick = onStartRadio)
        }
    }
}

@Composable
private fun NowPlayingWideContent(
    state: NowPlayingUiState,
    track: JellyfinItem,
    actions: NowPlayingActions,
    onArtistClick: (JellyfinItem) -> Unit,
    onStartRadio: () -> Unit,
) {
    // Deliberately independent of the compact layout's `showLyrics`: wide keeps the tab row up
    // unconditionally once lyrics exist, rather than swapping the artwork the way compact does.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lyricsShown = showLyrics && state.lyricsAvailable

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(Dimens.SpaceExtraLarge),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NowPlayingArtwork(track = track, size = ArtworkSizeWide)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            NowPlayingTitleBlock(track = track, onArtistClick = onArtistClick, alignment = Alignment.Start)

            Spacer(modifier = Modifier.height(Dimens.SpaceLarge))

            NowPlayingSeekBar(positionMs = state.positionMs, durationMs = state.durationMs, onSeekTo = actions.onSeekTo)

            Spacer(modifier = Modifier.height(Dimens.SpaceSmall))

            Row(verticalAlignment = Alignment.CenterVertically) {
                NowPlayingTransportRow(state = state, actions = actions)
                Spacer(modifier = Modifier.width(Dimens.SpaceLarge))
                NowPlayingFavoriteButton(isFavorite = track.userData.isFavorite, onClick = actions.onToggleFavorite)
                NowPlayingStartRadioButton(onClick = onStartRadio)
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceExtraLarge))

            NowPlayingRightPaneTabRow(
                lyricsAvailable = state.lyricsAvailable,
                showingLyrics = lyricsShown,
                onSelectQueue = { showLyrics = false },
                onSelectLyrics = { showLyrics = true },
            )
            Spacer(modifier = Modifier.height(Dimens.SpaceSmall))

            NowPlayingWidePaneBody(
                state = state,
                actions = actions,
                lyricsShown = lyricsShown,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The column's one flexible child, so the caller passes its `weight` in [modifier]. */
@Composable
private fun NowPlayingWidePaneBody(
    state: NowPlayingUiState,
    actions: NowPlayingActions,
    lyricsShown: Boolean,
    modifier: Modifier = Modifier,
) {
    if (lyricsShown && state.lyrics != null) {
        LyricsPane(
            lyrics = state.lyrics,
            activeLineIndex = state.activeLyricLineIndex,
            modifier = modifier,
        )
    } else {
        QueueList(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onJumpTo = actions.onJumpTo,
            onRemove = actions.onRemove,
            onMoveUp = { index -> actions.onMoveItem(index, index - 1) },
            onMoveDown = { index -> actions.onMoveItem(index, index + 1) },
            modifier = modifier,
        )
    }
}

@Composable
private fun NowPlayingRightPaneTabRow(
    lyricsAvailable: Boolean,
    showingLyrics: Boolean,
    onSelectQueue: () -> Unit,
    onSelectLyrics: () -> Unit,
) {
    if (!lyricsAvailable) {
        Text(
            text = stringResource(R.string.music_now_playing_queue),
            style = JellyfinTypeExtras.SectionTitle,
            color = MaterialTheme.colorScheme.onBackground,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge)) {
        NowPlayingTab(
            text = stringResource(R.string.music_now_playing_queue),
            selected = !showingLyrics,
            onClick = onSelectQueue,
        )
        NowPlayingTab(
            text = stringResource(R.string.music_now_playing_lyrics_tab),
            selected = showingLyrics,
            onClick = onSelectLyrics,
        )
    }
}

@Composable
private fun NowPlayingTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = JellyfinTypeExtras.SectionTitle,
        color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun NowPlayingStartRadioButton(onClick: () -> Unit) {
    GlassIconButton(
        icon = Icons.Filled.Radio,
        contentDescription = stringResource(R.string.music_now_playing_start_radio),
        onClick = onClick,
    )
}

@Composable
private fun NowPlayingArtwork(
    track: JellyfinItem,
    size: Dp,
) {
    JellyfinAsyncImage(
        url = track.primaryImageUrl,
        contentDescription = track.displayTitle,
        placeholderIcon = Icons.Outlined.MusicNote,
        modifier =
            Modifier
                .size(size)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
    )
}

@Composable
private fun NowPlayingTitleBlock(
    track: JellyfinItem,
    onArtistClick: (JellyfinItem) -> Unit,
    alignment: Alignment.Horizontal,
) {
    val textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = track.name,
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = textAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val artist = track.artistRefs.firstOrNull()
        val artistLine = artist?.name ?: track.artists.firstOrNull() ?: track.albumArtist
        if (artistLine != null) {
            Text(
                text = artistLine,
                style = NowPlayingArtistStyle,
                color = MaterialTheme.colorScheme.primary,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(top = Dimens.SpaceExtraSmall)
                        .then(
                            if (artist != null) {
                                Modifier.clickable {
                                    onArtistClick(
                                        JellyfinItem(id = artist.id, name = artist.name, type = ItemType.MUSIC_ARTIST),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
            )
        }

        track.album?.let { album ->
            Text(
                text = album,
                style = NowPlayingAlbumStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dimens.SpaceExtraSmall),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    var scrubMs by rememberSaveable { mutableStateOf<Long?>(null) }
    val shownMs = scrubMs ?: positionMs
    val fraction = if (durationMs > 0L) (shownMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { value -> scrubMs = (value * durationMs).toLong() },
            onValueChangeFinished = {
                scrubMs?.let(onSeekTo)
                scrubMs = null
            },
            enabled = durationMs > 0L,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatDuration(shownMs),
                style = NowPlayingTimeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = NowPlayingTimeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingTransportRow(
    state: NowPlayingUiState,
    actions: NowPlayingActions,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        GlassIconButton(
            icon = Icons.Filled.Shuffle,
            contentDescription =
                stringResource(
                    if (state.shuffleEnabled) {
                        R.string.music_now_playing_shuffle_off
                    } else {
                        R.string.music_now_playing_shuffle_on
                    },
                ),
            onClick = { actions.onSetShuffle(!state.shuffleEnabled) },
            tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else GlassIconTint,
        )
        GlassIconButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = stringResource(R.string.music_now_playing_previous),
            onClick = actions.onPrevious,
            size = Dimens.PillHeight,
        )
        PlayPauseButton(isPlaying = state.isPlaying, onClick = actions.onTogglePlayPause)
        GlassIconButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.music_now_playing_next),
            onClick = actions.onNext,
            size = Dimens.PillHeight,
        )
        GlassIconButton(
            icon = if (state.repeatMode == MusicRepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = stringResource(repeatContentDescription(state.repeatMode)),
            onClick = actions.onCycleRepeat,
            tint = if (state.repeatMode != MusicRepeatMode.OFF) MaterialTheme.colorScheme.primary else GlassIconTint,
        )
    }
}

private fun repeatContentDescription(mode: MusicRepeatMode): Int =
    when (mode) {
        MusicRepeatMode.OFF -> R.string.music_now_playing_repeat_off
        MusicRepeatMode.ALL -> R.string.music_now_playing_repeat_all
        MusicRepeatMode.ONE -> R.string.music_now_playing_repeat_one
    }

/**
 * Colours restated from `PlayerControls.PlayPauseButton`: `:feature:music` cannot depend on
 * `:player` to reuse it. Keep the two in step.
 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(PlayButtonSize),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = PlayGlyphColor),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription =
                stringResource(if (isPlaying) R.string.music_now_playing_pause else R.string.music_now_playing_play),
            modifier = Modifier.size(PlayIconSize),
        )
    }
}

@Composable
private fun NowPlayingFavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    GlassIconButton(
        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        contentDescription =
            stringResource(
                if (isFavorite) R.string.music_now_playing_favorite_remove else R.string.music_now_playing_favorite_add,
            ),
        onClick = onClick,
        tint = if (isFavorite) MaterialTheme.colorScheme.primary else GlassIconTint,
    )
}

/** Hours are not optional: a "track" can be a whole podcast episode. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** The chrome's own `TopNavMinWidth`. */
private val NowPlayingWideBreakpoint = 560.dp

/**
 * Width alone is not enough: a portrait tablet clears the breakpoint, and splitting there puts the
 * artwork in a half-width column. A taller-than-wide window wants the stacked layout whatever its
 * width. Extracted so both conditions stay unit-testable.
 */
internal fun isWideNowPlaying(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean = maxWidth >= NowPlayingWideBreakpoint && maxWidth > maxHeight

private val ArtworkSizeCompact = 320.dp
private val ArtworkSizeWide = 360.dp
private val PlayButtonSize = 72.dp
private val PlayIconSize = 32.dp
private val PlayGlyphColor = Color(0xFF101010)

private val NowPlayingArtistStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W500)
private val NowPlayingAlbumStyle = TextStyle(fontSize = 13.sp)
private val NowPlayingTimeStyle = TextStyle(fontSize = 11.sp)

private val PreviewActions =
    NowPlayingActions(
        onTogglePlayPause = {},
        onNext = {},
        onPrevious = {},
        onSeekTo = {},
        onSetShuffle = {},
        onCycleRepeat = {},
        onToggleFavorite = {},
        onJumpTo = {},
        onRemove = {},
        onMoveItem = { _, _ -> },
        onStop = {},
    )

private fun previewTrack() =
    JellyfinItem(
        id = "t1",
        name = "Fake Plastic Trees",
        type = ItemType.AUDIO,
        album = "The Bends",
        artists = listOf("Radiohead"),
        artistRefs = listOf(ArtistRef(id = "a1", name = "Radiohead")),
        userData = UserData(isFavorite = true),
    )

private fun previewState() =
    NowPlayingUiState(
        isIdle = false,
        track = previewTrack(),
        queue = listOf(previewTrack()),
        currentIndex = 0,
        isPlaying = true,
        positionMs = 90_000L,
        durationMs = 225_000L,
        shuffleEnabled = false,
        repeatMode = MusicRepeatMode.ALL,
    )

@Preview(
    name = "NowPlaying — compact",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun NowPlayingCompactPreview() {
    JellyfinTheme {
        NowPlayingContent(
            state = previewState(),
            actions = PreviewActions,
            onArtistClick = {},
            onStartRadio = {},
            onBack = {},
        )
    }
}

@Preview(
    name = "NowPlaying — wide",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 1024,
    heightDp = 640,
)
@Composable
private fun NowPlayingWidePreview() {
    JellyfinTheme {
        NowPlayingContent(
            state = previewState(),
            actions = PreviewActions,
            onArtistClick = {},
            onStartRadio = {},
            onBack = {},
        )
    }
}
