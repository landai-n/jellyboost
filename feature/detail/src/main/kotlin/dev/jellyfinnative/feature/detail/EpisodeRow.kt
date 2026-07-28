package dev.jellyfinnative.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.ui.component.ThumbCard
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * One episode in a season's list: the 16:9 [ThumbCard] artwork (with its played tick, progress bar
 * and download badge) on the left, number, title, runtime and synopsis on the right.
 *
 * Reusing `ThumbCard` for the artwork is deliberate — the watched indicator and download badge an
 * episode shows here have to be the exact same ones the home rows show.
 *
 * @param onClick opens the episode's own detail page.
 * @param onPlay starts playback directly. Worth its own button: from a season page the thing a
 *   user wants is almost always "play this one", and making them go through the episode page
 *   first adds a screen and a request for nothing.
 */
@Composable
internal fun EpisodeRow(
    episode: JellyfinItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        ThumbCard(
            item = episode,
            onClick = onClick,
            modifier = Modifier.width(EPISODE_THUMB_WIDTH),
            showTitle = false,
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

        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.detail_play_episode),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Slightly narrower than a home row's card — the synopsis needs the width more than the art does. */
private val EPISODE_THUMB_WIDTH = 160.dp

private const val OVERVIEW_LINES = 3
