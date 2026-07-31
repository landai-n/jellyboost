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
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
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
import dev.jellyfinnative.core.common.formatBytes
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
    downloadedBytes: Long? = null,
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
                downloadedBytes = downloadedBytes,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        DetailFacts(
            item = item,
            downloadState = downloadState,
            actions = actions,
            downloadedBytes = downloadedBytes,
            modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        )
    }
}

/** The things the header can do, bundled so the composables stay under the parameter limit. */
data class DetailActionHandlers(
    val onPlay: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onToggleFavorite: () -> Unit,
    /**
     * The SyncPlay group this page is acting for, or `null` when there is no group — which is the
     * ordinary case, and why the field is nullable rather than a flag beside a lambda (M11 Phase 4).
     * Carried in this bundle so no composable between here and the buttons grows a parameter for a
     * feature it does not otherwise know about.
     *
     * Non-null also changes what [onPlay] *means*: in a group a play is the group's play
     * (DECISIONS.md, 2026-07-31), which is why the Play button reads its label from here.
     */
    val group: DetailGroupActions? = null,
)

/**
 * The active group, and the one callback its queue buttons share.
 *
 * [groupName] is here because the buttons name the group they act on: "Play for Film night" says
 * what a tap does in a way "Play" cannot, and on a detail page a user may well have forgotten which
 * group they joined — which matters more now that Play itself is the group's play.
 */
data class DetailGroupActions(
    val groupName: String,
    val onAction: (GroupAction) -> Unit,
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
    downloadedBytes: Long? = null,
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

        MetadataLine(item = item, downloadState = downloadState, downloadedBytes = downloadedBytes)

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

/**
 * `2016 · 116 min · PG-13 · 8.4 · 4 seasons`, skipping whatever the server does not know.
 *
 * The size entry reads from the device rather than the server once a local copy is what the user
 * actually has: [downloadedBytes] is only trusted while [downloadState] itself is
 * [DownloadState.Downloaded], so a season mid-download (whose aggregate state is not yet
 * `Downloaded`) keeps showing the server's figure rather than a partial sum, and a fully-downloaded
 * container — which has no download row, and so no bytes, of its own — falls back to it too.
 */
@Composable
private fun MetadataLine(
    item: JellyfinItem,
    downloadState: DownloadState,
    modifier: Modifier = Modifier,
    downloadedBytes: Long? = null,
) {
    val parts =
        buildList {
            item.productionYear?.let { add(it.toString()) }
            item.runtimeMinutes?.let { add(stringResource(R.string.detail_runtime_minutes, it)) }
            if (downloadState is DownloadState.Downloaded && downloadedBytes != null && downloadedBytes > 0) {
                add(stringResource(R.string.detail_size_on_device, formatBytes(downloadedBytes)))
            } else {
                item.sizeBytes?.let { add(formatBytes(it)) }
            }
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
        PlayButton(item = item, group = actions.group, onClick = actions.onPlay)

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

        actions.group?.let { group -> GroupActionButtons(group = group) }
    }
}

/**
 * The page's primary action, which says who it plays *for*.
 *
 * In a group there is one Play button and it plays for the group — there is no second "Play for
 * group" button beside it any more, and no solo escape hatch on this page (DECISIONS.md,
 * 2026-07-31, superseding M11 Phase 4's "the group buttons join Play rather than replace it").
 * The rule the user set is that a group is a group: while one is joined, everything this page starts
 * is started for everyone in it, and the way out of that is to leave the group, which is one tap
 * away in the player and on the Groups screen.
 *
 * What must *not* happen is the meaning changing silently, so the label carries the group's name —
 * "Play for Film night" — and the group icon replaces the play triangle. [group] is non-null exactly
 * when a tap will reach the group (`ItemDetailUiState.groupTarget` resolved), so the label can never
 * promise a group play this page will not make.
 *
 * The resume position travels either way: watching something together starts where the person who
 * chose it had got to.
 */
@Composable
private fun PlayButton(
    item: JellyfinItem,
    group: DetailGroupActions?,
    onClick: () -> Unit,
) {
    val resume = item.userData.isResumable
    Button(onClick = onClick) {
        Icon(
            imageVector = if (group == null) Icons.Filled.PlayArrow else Icons.Outlined.Groups,
            contentDescription = null,
        )
        Text(
            text =
                when {
                    group == null && resume -> stringResource(R.string.detail_resume)
                    group == null -> stringResource(R.string.detail_play)
                    resume -> stringResource(R.string.detail_group_resume, group.groupName)
                    else -> stringResource(R.string.detail_group_play, group.groupName)
                },
            modifier = Modifier.padding(start = Dimens.SpaceSmall),
        )
    }
}

/**
 * The two group *queue* actions, drawn only while a SyncPlay group is active (M11 Phase 4).
 *
 * They join the Play button — which is itself the group's play, see [PlayButton] — because they are
 * the two things playing has no way to say: put this after what we are watching, or at the end. Both
 * stay outlined so the page keeps one primary action, and they sit in the same `FlowRow` as the
 * rest, so on a phone in portrait they simply wrap onto their own line.
 */
@Composable
private fun GroupActionButtons(group: DetailGroupActions) {
    OutlinedButton(onClick = { group.onAction(GroupAction.PLAY_NEXT) }) {
        Icon(imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null)
        Text(
            text = stringResource(R.string.detail_group_play_next),
            modifier = Modifier.padding(start = Dimens.SpaceSmall),
        )
    }

    OutlinedButton(onClick = { group.onAction(GroupAction.ADD_TO_QUEUE) }) {
        Icon(imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
        Text(
            text = stringResource(R.string.detail_group_add_to_queue),
            modifier = Modifier.padding(start = Dimens.SpaceSmall),
        )
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
