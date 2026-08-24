package dev.jellyboost.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.SQUARE_ASPECT_RATIO

/**
 * A 1:1 album card — Jellyfin's music artwork shape.
 *
 * Otherwise the same shape as [PosterCard]/[ThumbCard]: [MediaCardArtwork] carries the resume
 * progress bar, watched tick and [DownloadState] badge, and the title/subtitle sit underneath —
 * [JellyfinItem.displaySubtitle] already reads `albumArtist · year` for a [ItemType.MUSIC_ALBUM].
 *
 * @param width fixed card width, as a row of cards needs; [Dp.Unspecified] fills the available
 *   width instead, which is what an adaptive grid cell wants.
 */
@Composable
fun AlbumCard(
    item: JellyfinItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.PosterWidth,
    showTitle: Boolean = true,
) {
    Column(
        modifier = modifier.cardWidth(width).clickable(onClick = onClick),
    ) {
        MediaCardArtwork(
            imageUrl = item.primaryImageUrl,
            contentDescription = item.displayTitle,
            aspectRatio = SQUARE_ASPECT_RATIO,
            downloadState = item.downloadState,
            played = item.userData.played,
            progress = item.playbackProgress,
            placeholderIcon = Icons.Outlined.Album,
        )

        if (showTitle) {
            Spacer(modifier = Modifier.height(CardTitleGap))
            Text(
                text = item.displayTitle,
                style = CardTitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.displaySubtitle?.let { subtitle ->
                Spacer(modifier = Modifier.height(CardSubtitleGap))
                Text(
                    text = subtitle,
                    style = CardSubtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "AlbumCard", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun AlbumCardPreview() {
    JellyfinTheme {
        AlbumCard(
            item =
                JellyfinItem(
                    id = "1",
                    name = "The Bends",
                    type = ItemType.MUSIC_ALBUM,
                    albumArtist = "Radiohead",
                    productionYear = 1995,
                    userData = UserData(isFavorite = true),
                    downloadState = DownloadState.Downloaded,
                ),
            onClick = {},
        )
    }
}
