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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/** Diameter of the accent dot that precedes an eyebrow. */
private val EyebrowDotSize = 6.dp

/**
 * One horizontally-scrolling section of the home screen: a section title with an optional
 * "See all" action, above a `LazyRow` of cards.
 *
 * Rendering nothing when [items] is empty is intentional — jellyfin-web hides empty rows rather
 * than showing an empty shelf, and matching that is part of the M2 side-by-side check.
 *
 * @param key stable identity per item so the row survives recomposition and in-place user-data
 *   patches (the `UserDataEventBus` pattern from docs/PLAN.md).
 * @param eyebrow optional tracked-out caption above the title, preceded by an accent dot. Callers
 *   uppercase the text — the style tracks letters out but does not transform them, so a locale
 *   whose script has no case is unaffected.
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
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onSeeAll != null) {
                // Muted rather than accent-coloured: a row that shouts "See all" competes with the
                // artwork it is introducing, and every row in the app carries one.
                TextButton(
                    onClick = onSeeAll,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
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

/** The accent dot plus tracked-out caption that can sit above a section title. */
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
            eyebrow = "FROM YOUR LIBRARY",
            onSeeAll = {},
        ) { item ->
            PosterCard(item = item, onClick = {})
        }
    }
}
