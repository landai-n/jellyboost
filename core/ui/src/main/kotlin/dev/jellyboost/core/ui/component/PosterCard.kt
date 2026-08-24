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
const val POSTER_CARD_CONTENT_TYPE = "card-poster"

/**
 * @param width [Dp.Unspecified] fills the parent, which is what an adaptive grid cell wants.
 * @param selected a plain per-card `Boolean` rather than the selection set, so a toggle recomposes
 *   only the two cards whose flag flipped. `null` outside selection mode.
 * @param topStartBadge already formatted by the caller: the string resources belong to the screen
 *   showing the card, not to `:core:ui`.
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
    topStartBadge: String? = null,
    timeChipText: String? = null,
    ratingBadge: Float? = null,
) = MediaCard(
    shape = CardShape.Poster,
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

@Preview(name = "PosterCard — overlays", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun PosterCardOverlaysPreview() {
    JellyfinTheme {
        PosterCard(
            item =
                JellyfinItem(
                    id = "2",
                    name = "Dune",
                    type = ItemType.MOVIE,
                    productionYear = 2021,
                    communityRating = 8f,
                    runTimeTicks = 60_000_000_000L,
                    userData = UserData(playbackPositionTicks = 30_000_000_000L),
                ),
            onClick = {},
            topStartBadge = "4K",
            timeChipText = "48m left",
            ratingBadge = 8f,
        )
    }
}
