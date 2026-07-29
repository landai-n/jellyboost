package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme

/**
 * One horizontally-scrolling section of the home screen: a section title with an optional
 * "See all" action, above a `LazyRow` of cards.
 *
 * Rendering nothing when [items] is empty is intentional — jellyfin-web hides empty rows rather
 * than showing an empty shelf, and matching that is part of the M2 side-by-side check.
 *
 * @param key stable identity per item so the row survives recomposition and in-place user-data
 *   patches (the `UserDataEventBus` pattern from docs/PLAN.md).
 * @param contentType what kind of card [itemContent] draws. Every item in one row draws the same
 *   kind, so declaring it lets the `LazyRow` reuse a scrolled-off node instead of composing a fresh
 *   one; without it a lazy layout assumes every item may be different and reuses nothing.
 */
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentType: Any = DEFAULT_CARD_CONTENT_TYPE,
    onSeeAll: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text(text = "See all", style = MaterialTheme.typography.labelLarge)
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

/** Rows that do not say what they draw still reuse within themselves, just not across kinds. */
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
            onSeeAll = {},
        ) { item ->
            PosterCard(item = item, onClick = {})
        }
    }
}
