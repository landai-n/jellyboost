package dev.jellyboost.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.cardShadow

/**
 * A 1:1, circularly-clipped artist card (M13 Phase 2, docs/notes/music-m13-plan.md).
 *
 * Deliberately lighter than [AlbumCard]/[PosterCard]: an artist has no resume progress, no watched
 * state and today no [dev.jellyboost.core.common.model.DownloadState] of its own (artist rows are
 * upserted as download *parents* in M13 Phase 5, but that never drives a badge here — see the
 * download-badge KDoc on [MediaCardArtwork]), so there is nothing for [MediaCardArtwork]'s overlay
 * stack to draw. The name sits centred underneath rather than left-aligned, matching a circular
 * portrait rather than a rectangular poster.
 *
 * @param width fixed card width, as a row of cards needs; [Dp.Unspecified] fills the available
 *   width instead, which is what an adaptive grid cell wants.
 */
@Composable
fun ArtistCard(
    item: JellyfinItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.PosterWidth,
    showName: Boolean = true,
) {
    Column(
        modifier = modifier.cardWidth(width).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JellyfinAsyncImage(
            url = item.primaryImageUrl,
            contentDescription = item.displayTitle,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .cardShadow(CircleShape)
                    .clip(CircleShape),
            placeholderIcon = Icons.Outlined.Person,
        )

        if (showName) {
            Spacer(modifier = Modifier.height(CardTitleGap))
            Text(
                text = item.displayTitle,
                style = CardTitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "ArtistCard", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun ArtistCardPreview() {
    JellyfinTheme {
        ArtistCard(
            item = JellyfinItem(id = "1", name = "Radiohead", type = ItemType.MUSIC_ARTIST),
            onClick = {},
        )
    }
}
