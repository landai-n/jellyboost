package dev.jellyboost.feature.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.component.DownloadBadge
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * One track row — shared by [AlbumDetailScreen] and [PlaylistDetailScreen] (M13 Phase 2,
 * docs/notes/music-m13-plan.md: "reuse one `TrackRow` composable inside `:feature:music`").
 *
 * @param index the track number drawn at the row's start; `null` hides that column (nothing
 *   meaningful to show — e.g. a playlist row whose source order carries no per-track index).
 * @param onClick the row was tapped. Wired to the album/playlist screen's `onPlay(tracks,
 *   startIndex)` callback today, which is a no-op until M13 Phase 3 wires an actual queue — the
 *   signature exists now so Phase 3 only fills the callback in (docs/notes/music-m13-plan.md,
 *   Phase 2 spec item 5).
 * @param onToggleFavorite the favourite heart was tapped.
 */
@Composable
fun TrackRow(
    track: JellyfinItem,
    index: Int?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.SpaceLarge, vertical = Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(
            text = index?.toString().orEmpty(),
            style = TrackIndexStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(TrackIndexWidth),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = TrackTitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.displaySubtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = TrackSubtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        formatTrackDuration(track.runTimeTicks)?.let { duration ->
            Text(
                text = duration,
                style = TrackDurationStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        DownloadBadge(state = track.downloadState)

        TrackFavoriteButton(isFavorite = track.userData.isFavorite, onClick = onToggleFavorite)
    }
}

/** The row's trailing heart — [TrackRow]'s own affordance, sized to the minimum touch target. */
@Composable
private fun TrackFavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick, modifier = Modifier.size(Dimens.MinTouchTarget)) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.music_track_favorite_remove else R.string.music_track_favorite_add,
                ),
            tint = tint,
        )
    }
}

/** `3:45` — track lengths are short enough that whole minutes lose too much to be useful. */
internal fun formatTrackDuration(runTimeTicks: Long?): String? {
    val totalSeconds = runTimeTicks?.takeIf { it > 0L }?.let { it / TICKS_PER_SECOND } ?: return null
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val TICKS_PER_SECOND = 10_000_000L
private const val SECONDS_PER_MINUTE = 60L

private val TrackIndexWidth = 24.dp

private val TrackIndexStyle = TextStyle(fontSize = 13.sp)

private val TrackTitleStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500, lineHeight = 18.sp)

private val TrackSubtitleStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

private val TrackDurationStyle = TextStyle(fontSize = 12.sp)

@Preview(name = "TrackRow", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun TrackRowPreview() {
    JellyfinTheme {
        TrackRow(
            track =
                JellyfinItem(
                    id = "1",
                    name = "Fake Plastic Trees",
                    type = ItemType.AUDIO,
                    artists = listOf("Radiohead"),
                    indexNumber = 4,
                    runTimeTicks = 3 * 60 * TICKS_PER_SECOND + 45 * TICKS_PER_SECOND,
                    userData = UserData(isFavorite = true),
                    downloadState = DownloadState.Downloaded,
                ),
            index = 4,
            onClick = {},
            onToggleFavorite = {},
        )
    }
}
