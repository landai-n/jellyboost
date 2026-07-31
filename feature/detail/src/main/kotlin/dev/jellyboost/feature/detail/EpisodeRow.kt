package dev.jellyboost.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.selectableCardClick
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * One episode in a season's list: the 16:9 [ThumbCard] artwork (with its played tick, progress bar
 * and download badge) on the left, number, title, runtime and synopsis on the right.
 *
 * Reusing `ThumbCard` for the artwork is deliberate — the watched indicator and download badge an
 * episode shows here have to be the exact same ones the home rows show.
 *
 * @param onClick opens the episode's own detail page — or, while the list is in batch-selection
 *   mode, toggles this row (the caller decides; see `ItemDetailScreen`).
 * @param onPlay starts playback directly. Worth its own button: from a season page the thing a
 *   user wants is almost always "play this one", and making them go through the episode page
 *   first adds a screen and a request for nothing. Replaced by the selection checkbox while the
 *   mode is on: play is a one-item action, and the mode is about many.
 * @param onLongClick enters batch-selection mode; `null` on lists that do not offer it.
 * @param selected `null` outside selection mode. Selected rows get a `secondaryContainer` wash
 *   rather than the artwork scrim a poster gets — a list row's identity is its text, and dimming
 *   the thumbnail alone would not read at a glance down a column of forty.
 * @param compact narrows the thumbnail to [EPISODE_THUMB_WIDTH_COMPACT] on a phone-width viewport
 *   (`ItemDetailScreen`'s `COMPACT_MAX_WIDTH`) — at 360dp the full-width thumb otherwise leaves the
 *   text column under ~130dp, truncating a title after two words. Defaults to `false` so existing
 *   call sites and previews keep the wider, tablet-friendly thumb.
 */
@Composable
internal fun EpisodeRow(
    episode: JellyfinItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    compact: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (selected == true) {
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier
                    },
                ).then(
                    if (onLongClick == null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier.selectableCardClick(onClick = onClick, onLongClick = onLongClick)
                    },
                ).padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        ThumbCard(
            item = episode,
            onClick = onClick,
            modifier = Modifier.width(if (compact) EPISODE_THUMB_WIDTH_COMPACT else EPISODE_THUMB_WIDTH),
            showTitle = false,
            onLongClick = onLongClick,
            selected = selected,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        ) {
            Text(
                text = listOfNotNull(episode.episodeLabel, episode.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            episode.runtimeMinutes?.let { minutes ->
                Text(
                    text = stringResource(R.string.detail_runtime_minutes, minutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = OVERVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (selected == null) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.detail_play_episode),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            // `onCheckedChange = null` on purpose: the whole row is already the click target, and a
            // second one inside it would let a tap land on the box but not on the row it belongs to.
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** Slightly narrower than a home row's card — the synopsis needs the width more than the art does. */
private val EPISODE_THUMB_WIDTH = 160.dp

/**
 * The [compact] thumb width — narrow enough that at a 360dp viewport (16dp screen padding either
 * side, [Dimens.SpaceMedium] between thumb and text) the text column still keeps a usable ~130dp+.
 * `ThumbCard`'s `aspectRatio` modifier derives the 16:9 height from this width automatically.
 */
private val EPISODE_THUMB_WIDTH_COMPACT = 128.dp

private const val OVERVIEW_LINES = 3

@Preview(name = "EpisodeRow", showBackground = true, widthDp = 420)
@Composable
private fun EpisodeRowPreview() {
    JellyfinTheme {
        EpisodeRow(episode = previewEpisode, onClick = {}, onPlay = {})
    }
}

@Preview(name = "EpisodeRow — compact", showBackground = true, widthDp = 360)
@Composable
private fun EpisodeRowCompactPreview() {
    JellyfinTheme {
        EpisodeRow(episode = previewEpisode, onClick = {}, onPlay = {}, compact = true)
    }
}

private val previewEpisode =
    JellyfinItem(
        id = "2",
        name = "The Bicameral Mind",
        type = ItemType.EPISODE,
        overview =
            "Dolores and Maeve approach full consciousness as the board moves against Ford, and the " +
                "season's threads converge on the maze.",
        indexNumber = 10,
        parentIndexNumber = 1,
        runTimeTicks = 54_000_000_000L,
    )
