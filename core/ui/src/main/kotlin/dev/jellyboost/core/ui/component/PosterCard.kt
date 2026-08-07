package dev.jellyboost.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.text.subtitleLine
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.POSTER_ASPECT_RATIO

/**
 * Lazy-list `contentType` for every [PosterCard] (DUP-15) — lets a `LazyRow`/`LazyColumn` reuse a
 * scrolled-off poster node instead of composing a fresh one when the next item is also a poster.
 */
const val POSTER_CARD_CONTENT_TYPE = "card-poster"

/**
 * A 2:3 poster card — the default card for movies, series and seasons, matching the poster shape
 * jellyfin-web uses on its home and library screens.
 *
 * Shows the item's primary artwork with the resume progress bar, watched tick and
 * [dev.jellyboost.core.common.model.DownloadState] badge overlaid, plus title and subtitle
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
 * @param topStartBadge optional overlay metadata — see [MediaCardArtwork]. The card does not derive
 *   these from [item]: they are already-formatted strings, and formatting them takes string
 *   resources that belong to the screen showing the card rather than to `:core:ui`.
 *
 * The whole card is **one** semantics node with an authored description — type, untruncated title,
 * subtitle, progress, rating, download and watched state — plus real `selected` semantics in
 * selection mode. See [mediaCardSemantics] for why everything inside it is silenced.
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
) {
    val description =
        mediaCardDescription(
            item = item,
            badge = topStartBadge,
            timeChipText = timeChipText,
            ratingBadge = ratingBadge,
        )
    Column(
        modifier =
            modifier
                .cardWidth(width)
                .then(mediaCardSemantics(description = description, selected = selected))
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.selectableCardClick(onClick = onClick, onLongClick = onLongClick)
                    },
                ),
    ) {
        MediaCardArtwork(
            imageUrl = item.primaryImageUrl,
            contentDescription = null,
            aspectRatio = POSTER_ASPECT_RATIO,
            downloadState = item.downloadState,
            played = item.userData.played,
            progress = item.playbackProgress,
            placeholderIcon = Icons.Outlined.Movie,
            selected = selected,
            topStartBadge = topStartBadge,
            timeChipText = timeChipText,
            ratingBadge = ratingBadge,
        )

        if (showTitle) {
            CardTitleBlock(title = item.displayTitle, subtitle = item.subtitleLine())
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
