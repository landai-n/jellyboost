package dev.jellyboost.feature.music.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.music.LyricLine
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * Always renders a list, never an empty state: the caller decides whether to show this at all
 * (`NowPlayingUiState.lyricsAvailable`).
 *
 * @param activeLineIndex `null` for unsynced lyrics — nothing highlighted, no auto-scroll — or
 *   before playback reaches the first timed line.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics,
    activeLineIndex: Int?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex != null) {
            listState.animateScrollToItem(activeLineIndex)
        }
    }

    if (lyrics.lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Dimens.SpaceExtraLarge),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            LyricLineText(line = line, isActive = index == activeLineIndex)
        }
    }
}

@Composable
private fun LyricLineText(
    line: LyricLine,
    isActive: Boolean,
) {
    Text(
        text = line.text,
        style = if (isActive) ActiveLineStyle else InactiveLineStyle,
        color =
            if (isActive) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
    )
}

private val ActiveLineStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W700, lineHeight = 28.sp)
private val InactiveLineStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.W400, lineHeight = 26.sp)

@Preview(
    name = "LyricsPane — synced",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 420,
    heightDp = 500,
)
@Composable
private fun LyricsPaneSyncedPreview() {
    JellyfinTheme {
        LyricsPane(
            lyrics =
                Lyrics(
                    lines =
                        listOf(
                            LyricLine(startTicks = 0L, text = "A green plastic watering can"),
                            LyricLine(startTicks = 30_000_000L, text = "For a fake Chinese rubber plant"),
                            LyricLine(startTicks = 60_000_000L, text = "In the fake plastic earth"),
                        ),
                    isSynced = true,
                ),
            activeLineIndex = 1,
        )
    }
}

@Preview(
    name = "LyricsPane — unsynced",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 420,
    heightDp = 500,
)
@Composable
private fun LyricsPaneUnsyncedPreview() {
    JellyfinTheme {
        LyricsPane(
            lyrics =
                Lyrics(
                    lines =
                        listOf(
                            LyricLine(startTicks = null, text = "A green plastic watering can"),
                            LyricLine(startTicks = null, text = "For a fake Chinese rubber plant"),
                        ),
                    isSynced = false,
                ),
            activeLineIndex = null,
        )
    }
}
