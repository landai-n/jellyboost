package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.core.ui.theme.POSTER_ASPECT_RATIO

/**
 * A 2:3 poster card — the default card for movies, series and seasons, matching the poster shape
 * jellyfin-web uses on its home and library screens.
 *
 * Shows the item's primary artwork with the resume progress bar, watched tick and
 * [dev.jellyfinnative.core.common.model.DownloadState] badge overlaid, plus title and subtitle
 * underneath.
 *
 * @param width fixed card width, as a row of cards needs; [Dp.Unspecified] fills the available
 *   width instead, which is what an adaptive grid cell wants (and saves the caller a
 *   `BoxWithConstraints` per cell).
 * @param onLongClick offered by lists that support batch selection; `null` everywhere else, which
 *   leaves the card with a plain `clickable` and no combined-gesture detector at all.
 * @param selected `null` when the list is not in selection mode — see [MediaCardArtwork]. A plain
 *   `Boolean` per card (rather than the selection set itself) is what keeps a toggle from
 *   recomposing every visible cell: only the two cards whose flag actually flipped have a changed
 *   parameter.
 */
@Composable
fun PosterCard(
    item: JellyfinItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.PosterWidth,
    showTitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    Column(
        modifier =
            modifier
                .cardWidth(width)
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier.selectableCardClick(onClick = onClick, onLongClick = onLongClick)
                    },
                ),
    ) {
        MediaCardArtwork(
            imageUrl = item.primaryImageUrl,
            contentDescription = item.displayTitle,
            aspectRatio = POSTER_ASPECT_RATIO,
            downloadState = item.downloadState,
            played = item.userData.played,
            progress = item.playbackProgress,
            placeholderIcon = Icons.Outlined.Movie,
            selected = selected,
        )

        if (showTitle) {
            Spacer(modifier = Modifier.height(Dimens.SpaceSmall))
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.displaySubtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "PosterCard", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun PosterCardPreview() {
    JellyfinTheme {
        PosterCard(
            item =
                JellyfinItem(
                    id = "1",
                    name = "Arrival",
                    type = ItemType.MOVIE,
                    productionYear = 2016,
                    runTimeTicks = 60_000_000_000L,
                    userData = UserData(playbackPositionTicks = 18_000_000_000L),
                    downloadState = DownloadState.Downloaded,
                ),
            onClick = {},
        )
    }
}
