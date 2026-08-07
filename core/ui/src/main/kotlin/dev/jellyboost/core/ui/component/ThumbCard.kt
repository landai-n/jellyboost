package dev.jellyboost.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
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
import dev.jellyboost.core.ui.theme.THUMB_ASPECT_RATIO

/**
 * Lazy-list `contentType` for every [ThumbCard] (DUP-15) — lets a `LazyRow`/`LazyColumn` reuse a
 * scrolled-off thumb node instead of composing a fresh one when the next item is also a thumb.
 */
const val THUMB_CARD_CONTENT_TYPE = "card-thumb"

/**
 * A 16:9 thumbnail card — used wherever jellyfin-web shows landscape artwork: *Continue watching*,
 * *Next up* and episode lists.
 *
 * Falls back through thumb → backdrop → primary artwork so a row never degrades into placeholders
 * just because a server has no dedicated thumb image.
 *
 * @param width fixed card width, as a row of cards needs; [Dp.Unspecified] fills the available
 *   width instead, which is what an adaptive grid cell wants.
 * @param onLongClick offered by lists that support batch selection; `null` everywhere else.
 * @param selected `null` when the list is not in selection mode — see [MediaCardArtwork].
 * @param topStartBadge optional overlay metadata — see [MediaCardArtwork]. Formatted by the caller,
 *   which is the screen that owns the string resources ("S1 · E10", "22m left").
 */
@Composable
fun ThumbCard(
    item: JellyfinItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.ThumbWidth,
    showTitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    topStartBadge: String? = null,
    timeChipText: String? = null,
    ratingBadge: Float? = null,
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
            imageUrl = item.thumbImageUrl ?: item.backdropImageUrl ?: item.primaryImageUrl,
            contentDescription = item.displayTitle,
            aspectRatio = THUMB_ASPECT_RATIO,
            downloadState = item.downloadState,
            played = item.userData.played,
            progress = item.playbackProgress,
            placeholderIcon = Icons.Outlined.Tv,
            selected = selected,
            topStartBadge = topStartBadge,
            timeChipText = timeChipText,
            ratingBadge = ratingBadge,
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

@Preview(name = "ThumbCard", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun ThumbCardPreview() {
    JellyfinTheme {
        ThumbCard(
            item =
                JellyfinItem(
                    id = "2",
                    name = "The Bicameral Mind",
                    type = ItemType.EPISODE,
                    seriesName = "Westworld",
                    indexNumber = 10,
                    parentIndexNumber = 1,
                    runTimeTicks = 54_000_000_000L,
                    userData = UserData(playbackPositionTicks = 27_000_000_000L),
                    downloadState = DownloadState.Downloading(progress = 0.7f),
                ),
            onClick = {},
        )
    }
}

@Preview(name = "ThumbCard — overlays", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun ThumbCardOverlaysPreview() {
    JellyfinTheme {
        ThumbCard(
            item =
                JellyfinItem(
                    id = "3",
                    name = "The Original",
                    type = ItemType.EPISODE,
                    seriesName = "Westworld",
                    indexNumber = 1,
                    parentIndexNumber = 1,
                    runTimeTicks = 54_000_000_000L,
                    userData = UserData(playbackPositionTicks = 27_000_000_000L),
                ),
            onClick = {},
            topStartBadge = "S1 · E1",
            timeChipText = "27m left",
        )
    }
}
