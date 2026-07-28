package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.core.ui.theme.THUMB_ASPECT_RATIO

/**
 * A user library tile for the home screen's *My Media* row, matching jellyfin-web's landscape
 * library cards.
 */
@Composable
fun LibraryCard(
    library: LibraryView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.ThumbWidth,
) {
    Column(
        modifier =
            modifier
                .width(width)
                .clickable(onClick = onClick),
    ) {
        JellyfinAsyncImage(
            url = library.thumbImageUrl ?: library.primaryImageUrl,
            contentDescription = library.name,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(THUMB_ASPECT_RATIO)
                    .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
            contentScale = ContentScale.Crop,
            placeholderIcon =
                when (library.collectionType) {
                    CollectionKind.TVSHOWS -> Icons.Outlined.Tv
                    else -> Icons.Outlined.Movie
                },
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceSmall))
        Text(
            text = library.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "LibraryCard", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun LibraryCardPreview() {
    JellyfinTheme {
        LibraryCard(
            library = LibraryView(id = "lib-1", name = "Movies", collectionType = CollectionKind.MOVIES),
            onClick = {},
        )
    }
}
