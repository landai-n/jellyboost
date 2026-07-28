package dev.jellyfinnative.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.PersonKind
import dev.jellyfinnative.core.ui.component.JellyfinAsyncImage
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.POSTER_ASPECT_RATIO
import java.util.Locale

/**
 * The block under the backdrop: poster, title, metadata line, action buttons, taglines and
 * overview.
 *
 * On a wide screen (this project's test device is a tablet) the poster sits beside the text rather
 * than above it — the same rearrangement jellyfin-web makes on a desktop viewport.
 */
@Composable
internal fun DetailHeader(
    item: JellyfinItem,
    isWide: Boolean,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    modifier: Modifier = Modifier,
) {
    if (isWide) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
        ) {
            DetailPoster(item = item, modifier = Modifier.width(WIDE_POSTER_WIDTH))
            DetailFacts(
                item = item,
                downloadState = downloadState,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        DetailFacts(
            item = item,
            downloadState = downloadState,
            actions = actions,
            modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        )
    }
}

/** The four things the header can do, bundled so the composables stay under the parameter limit. */
data class DetailActionHandlers(
    val onPlay: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onToggleFavorite: () -> Unit,
)

@Composable
private fun DetailPoster(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    JellyfinAsyncImage(
        url = item.primaryImageUrl,
        contentDescription = null,
        modifier =
            modifier
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun DetailFacts(
    item: JellyfinItem,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        item.subtitleLine()?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MetadataLine(item = item)

        item.playbackProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                drawStopIndicator = {},
            )
        }

        DetailActions(item = item, downloadState = downloadState, actions = actions)

        item.taglines.firstOrNull()?.let { tagline ->
            Text(
                text = tagline,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
            )
        }

        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
            )
        }

        item.creditLine()?.let { credits ->
            Text(
                text = credits,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
            )
        }

        if (item.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
                item.genres.forEach { genre ->
                    // Not a filter yet — genre filtering lives on the library grid (M3).
                    AssistChip(onClick = {}, enabled = false, label = { Text(text = genre) })
                }
            }
        }
    }
}

/** `2016 · 116 min · PG-13 · 8.4 · 4 seasons`, skipping whatever the server does not know. */
@Composable
private fun MetadataLine(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    val parts =
        buildList {
            item.productionYear?.let { add(it.toString()) }
            item.runtimeMinutes?.let { add(stringResource(R.string.detail_runtime_minutes, it)) }
            item.officialRating?.let(::add)
            item.communityRating?.let { add(String.format(Locale.US, "%.1f", it)) }
            item.childCountLabel()?.let(::add)
            item.remainingMinutes?.let { add(stringResource(R.string.detail_remaining_minutes, it)) }
        }
    if (parts.isEmpty()) return

    Text(
        text = parts.joinToString(SEPARATOR),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun DetailActions(
    item: JellyfinItem,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Button(onClick = actions.onPlay) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            Text(
                text =
                    stringResource(
                        if (item.userData.isResumable) R.string.detail_resume else R.string.detail_play,
                    ),
                modifier = Modifier.padding(start = Dimens.SpaceSmall),
            )
        }

        val watched = item.userData.played
        OutlinedButton(onClick = actions.onToggleWatched) {
            Icon(
                imageVector = if (watched) Icons.Filled.Check else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = accent(watched),
            )
            Text(
                text =
                    stringResource(
                        if (watched) R.string.detail_mark_unwatched else R.string.detail_mark_watched,
                    ),
                modifier = Modifier.padding(start = Dimens.SpaceSmall),
            )
        }

        val favorite = item.userData.isFavorite
        OutlinedButton(onClick = actions.onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription =
                    stringResource(
                        if (favorite) R.string.detail_remove_favorite else R.string.detail_add_favorite,
                    ),
                tint = accent(favorite),
            )
        }

        DownloadButton(state = downloadState, onClick = actions.onDownload)
    }
}

/**
 * The Download button, which is really four buttons wearing one coat.
 *
 * Its icon and its label say what tapping it does *now*: download, or remove what is already there
 * (or being fetched). A downloading item shows the same determinate ring the cards' `DownloadBadge`
 * draws, so the two agree at a glance.
 */
@Composable
private fun DownloadButton(
    state: DownloadState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        when (state) {
            is DownloadState.Downloading ->
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(Dimens.BadgeSize),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )

            else ->
                Icon(
                    imageVector = state.icon(),
                    contentDescription = null,
                    tint =
                        if (state is DownloadState.Downloaded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                )
        }
        Text(
            text = stringResource(state.labelRes()),
            modifier = Modifier.padding(start = Dimens.SpaceSmall),
        )
    }
}

private fun DownloadState.icon() =
    when (this) {
        is DownloadState.NotDownloaded -> Icons.Outlined.Download
        is DownloadState.Downloaded -> Icons.Filled.DownloadDone
        is DownloadState.Failed -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Downloading
    }

/**
 * The label says what a tap *does*, not what the state is — the state is already the icon.
 *
 * Cancelling a queued, running or paused download and deleting a finished one are the same
 * operation with different words for it, which is why they share a handler.
 */
private fun DownloadState.labelRes(): Int =
    when (this) {
        is DownloadState.NotDownloaded -> R.string.detail_download
        is DownloadState.Queued, is DownloadState.Downloading, is DownloadState.Paused ->
            R.string.detail_download_cancel

        is DownloadState.Downloaded -> R.string.detail_download_remove
        is DownloadState.Failed -> R.string.detail_download_retry
    }

@Composable
private fun accent(active: Boolean) =
    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun JellyfinItem.childCountLabel(): String? {
    val count = childCount ?: return null
    return when (type) {
        ItemType.SERIES -> pluralStringResource(R.plurals.detail_season_count, count, count)
        ItemType.SEASON -> pluralStringResource(R.plurals.detail_episode_count, count, count)
        else -> null
    }
}

/** `S1:E4 · Trompe L'Oeil` for an episode, the series name for a season, nothing otherwise. */
private fun JellyfinItem.subtitleLine(): String? =
    when (type) {
        ItemType.EPISODE -> listOfNotNull(episodeLabel, name).joinToString(SEPARATOR).ifBlank { null }
        ItemType.SEASON -> seriesName
        else -> null
    }

/** `Directed by X · A, B, C` — the cheap version of a cast row, from the `PEOPLE` field. */
@Composable
private fun JellyfinItem.creditLine(): String? {
    val director = people.firstOrNull { it.kind == PersonKind.DIRECTOR }?.name
    val cast =
        people
            .filter { it.kind == PersonKind.ACTOR || it.kind == PersonKind.GUEST_STAR }
            .take(TOP_BILLED)
            .map { it.name }
    val parts =
        buildList {
            director?.let { add(stringResource(R.string.detail_directed_by, it)) }
            if (cast.isNotEmpty()) add(cast.joinToString(", "))
        }
    return parts.joinToString(SEPARATOR).ifBlank { null }
}

/** How many actors the credit line names before it stops. */
private const val TOP_BILLED = 4

/** Interpunct with hair spaces — the separator jellyfin-web uses between metadata facts. */
private const val SEPARATOR = " · "

private val WIDE_POSTER_WIDTH = 200.dp

/** Long-form text stops growing here; a full-width paragraph on a tablet is unreadable. */
private val TEXT_MAX_WIDTH = 680.dp
