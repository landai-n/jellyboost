package dev.jellyboost.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/** Lazy-list `contentType`: without one a lazy layout reuses no scrolled-off node. */
const val THUMB_CARD_CONTENT_TYPE = "card-thumb"

/**
 * @param onClick `null` when the card is inside something already clickable (`EpisodeRow`): it then
 *   draws the same artwork with no click target and no semantics, leaving the row one node.
 * @param width [Dp.Unspecified] fills the parent, which is what an adaptive grid cell wants.
 * @param onLongClick ignored when [onClick] is `null` — a card that ignores a tap cannot claim a
 *   long press.
 * @param topStartBadge already formatted by the caller, which owns the string resources.
 */
@Composable
fun ThumbCard(
    item: JellyfinItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.ThumbWidth,
    showTitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    topStartBadge: String? = null,
    timeChipText: String? = null,
    ratingBadge: Float? = null,
) = MediaCard(
    shape = CardShape.Thumb,
    item = item,
    onClick = onClick,
    modifier = modifier,
    width = width,
    showTitle = showTitle,
    onLongClick = onLongClick,
    overlays =
        CardOverlayFacts(
            selected = selected,
            topStartBadge = topStartBadge,
            timeChipText = timeChipText,
            ratingBadge = ratingBadge,
        ),
)

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
