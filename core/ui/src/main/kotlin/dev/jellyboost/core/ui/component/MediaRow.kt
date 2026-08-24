package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

private val EyebrowDotSize = 6.dp

/**
 * Rendering nothing when [items] is empty is intentional: an empty shelf is worse than no row.
 *
 * @param key stable identity per item, so the row survives in-place user-data patches (the
 *   `UserDataEventBus` pattern).
 * @param eyebrow callers uppercase the text — the style tracks letters out without transforming
 *   them, so a caseless script is unaffected.
 * @param contentType without one a lazy layout assumes every item differs and reuses nothing.
 */
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentType: Any = DEFAULT_CARD_CONTENT_TYPE,
    eyebrow: String? = null,
    onSeeAll: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            SectionEyebrow(
                text = eyebrow,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Spacer(modifier = Modifier.height(Dimens.SpaceSmall))
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = JellyfinTypeExtras.SectionTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The heading is what makes TalkBack's heading-jump work: without it the fourth row
                // is three rows of cards away. Spoken untruncated, whatever the line had room for.
                modifier =
                    Modifier.weight(1f, fill = false).semantics {
                        heading()
                        contentDescription = title
                    },
            )
            if (onSeeAll != null) {
                val seeAllDescription = stringResource(R.string.media_row_see_all_section, title)
                TextButton(
                    onClick = onSeeAll,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    // Named per section: every row carries one, and "See all" alone says all of what?
                    modifier = Modifier.semantics { contentDescription = seeAllDescription },
                ) {
                    Text(text = stringResource(R.string.media_row_see_all), style = JellyfinTypeExtras.SeeAll)
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceSmall))

        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        ) {
            items(items = items, key = key, contentType = { contentType }) { item ->
                itemContent(item)
            }
        }
    }
}

@Composable
private fun SectionEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Box(
            modifier =
                Modifier
                    .size(EyebrowDotSize)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        )
        Text(
            text = text,
            style = JellyfinTypeExtras.Eyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val DEFAULT_CARD_CONTENT_TYPE = "media-card"

@Preview(name = "MediaRow", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun MediaRowPreview() {
    val previewItems =
        listOf(
            JellyfinItem(id = "1", name = "Arrival", type = ItemType.MOVIE, productionYear = 2016),
            JellyfinItem(id = "2", name = "Dune", type = ItemType.MOVIE, productionYear = 2021),
            JellyfinItem(id = "3", name = "Sicario", type = ItemType.MOVIE, productionYear = 2015),
        )
    JellyfinTheme {
        MediaRow(
            title = "Latest Movies",
            items = previewItems,
            key = JellyfinItem::id,
            eyebrow = "FROM YOUR LIBRARY",
            onSeeAll = {},
        ) { item ->
            PosterCard(item = item, onClick = {})
        }
    }
}
