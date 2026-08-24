package dev.jellyboost.feature.music.nowplaying

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.feature.music.R

/**
 * Takes no ViewModel of its own, unlike `SyncPlayQueueSheet`: `NowPlayingScreen` already collects
 * everything here, and a second collector of the same `@Singleton` controller state is redundant.
 *
 * TODO: reorder is up/down buttons rather than a drag, because no drag-to-reorder pattern exists
 *  anywhere in this codebase to mirror. Worth revisiting once a second call site wants one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<JellyfinItem>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        QueueSheetContent(
            queue = queue,
            currentIndex = currentIndex,
            onJumpTo = onJumpTo,
            onRemove = onRemove,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onStop = onStop,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<JellyfinItem>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val listMaxHeight = queueListMaxHeight(maxHeight)

        Column(
            modifier =
                Modifier
                    .widthIn(max = SHEET_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceExtraLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            QueueHeader(count = queue.size, onStop = onStop, onClose = onClose)

            HorizontalDivider()

            QueueList(
                queue = queue,
                currentIndex = currentIndex,
                onJumpTo = onJumpTo,
                onRemove = onRemove,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                modifier = Modifier.heightIn(max = listMaxHeight),
            )
        }
    }
}

/** See `SyncPlayQueueSheet.queueListMaxHeight` — the same landscape-sheet-height problem. */
internal fun queueListMaxHeight(maxHeight: Dp): Dp = minOf(LIST_MAX_HEIGHT, maxHeight * LIST_MAX_HEIGHT_FRACTION)

/**
 * Stop must stay *before* Close: it throws the queue away, and putting it last would put a
 * queue-destroying button where the muscle memory for "close this sheet" already is.
 */
@Composable
private fun QueueHeader(
    count: Int,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.music_now_playing_queue),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = pluralStringResource(R.plurals.music_now_playing_queue_count, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.music_now_playing_stop),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.music_now_playing_queue_close),
            )
        }
    }
}

/**
 * Shared by [QueueSheetContent] and `NowPlayingScreen`'s wide inline pane, so the two cannot drift
 * apart on how a row looks.
 */
@Composable
internal fun QueueList(
    queue: List<JellyfinItem>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) {
        Text(
            text = stringResource(R.string.music_now_playing_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = Dimens.SpaceLarge),
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        // Position-qualified keys: a queue built from a playlist can hold the same track twice,
        // and duplicate lazy keys crash the composition. Same convention as PlaylistDetailScreen.
        itemsIndexed(items = queue, key = { index, track -> "$index:${track.id}" }) { index, track ->
            QueueTrackRow(
                track = track,
                isPlaying = index == currentIndex,
                canMoveUp = index > 0,
                canMoveDown = index < queue.lastIndex,
                onJumpTo = { onJumpTo(index) },
                onMoveUp = { onMoveUp(index) },
                onMoveDown = { onMoveDown(index) },
                onRemove = { onRemove(index) },
            )
        }
    }
}

@Composable
private fun QueueTrackRow(
    track: JellyfinItem,
    isPlaying: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onJumpTo: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val background =
        if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = NOW_PLAYING_TINT_ALPHA) else Color.Transparent

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                .background(background)
                .clickable(onClick = onJumpTo)
                .padding(Dimens.SpaceExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        QueueTrackThumb(url = track.primaryImageUrl)

        QueueTrackLabels(track = track, modifier = Modifier.weight(1f))

        if (isPlaying) {
            Icon(
                imageVector = Icons.Filled.Equalizer,
                contentDescription = stringResource(R.string.music_now_playing_queue_playing),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        QueueRowActions(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun QueueTrackThumb(url: String?) {
    val thumbShape = RoundedCornerShape(Dimens.CardCornerRadius)
    JellyfinAsyncImage(
        url = url,
        contentDescription = null,
        modifier =
            Modifier
                .width(ROW_THUMB_SIZE)
                .heightIn(max = ROW_THUMB_SIZE)
                .clip(thumbShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.ArtworkInnerHairline, thumbShape),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun QueueTrackLabels(
    track: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        track.displaySubtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One overflow, not three inline buttons: three 48dp `IconButton`s over the row's 56dp artwork
 * would leave the flexible title column ellipsising after a couple of words on a phone-width sheet.
 *
 * The icon must be tinted **explicitly**: [QueueList] is also drawn inline in `NowPlayingScreen`'s
 * wide pane, which has no `Surface` ancestor, so `LocalContentColor` falls back to Material's bare
 * `Color.Black` — invisible on the app's `#101010`.
 */
@Composable
private fun QueueRowActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // The menu must be a sibling of the button *inside its own Box*: a DropdownMenu anchors to its
    // layout parent, and without the wrapper that parent is the whole row.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                // No "more options" string exists in this module; the sheet's own title stands in.
                contentDescription = stringResource(R.string.music_now_playing_queue),
                tint = GlassIconTint,
            )
        }

        // `LibrarySortMenu`'s dressing: the theme's `surface`, not M3's `surfaceContainer`.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(GlassDefaults.HairlineWidth, GlassDefaults.PanelHairline),
        ) {
            QueueRowMenuItem(
                labelRes = R.string.music_now_playing_queue_move_up,
                icon = Icons.Outlined.ArrowUpward,
                enabled = canMoveUp,
                onClick = {
                    expanded = false
                    onMoveUp()
                },
            )
            QueueRowMenuItem(
                labelRes = R.string.music_now_playing_queue_move_down,
                icon = Icons.Outlined.ArrowDownward,
                enabled = canMoveDown,
                onClick = {
                    expanded = false
                    onMoveDown()
                },
            )
            QueueRowMenuItem(
                labelRes = R.string.music_now_playing_queue_remove,
                icon = Icons.Outlined.Delete,
                enabled = true,
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * `Role.Button` must be on the item, not its icon: `DropdownMenuItem`'s `clickable` merges its
 * descendants and sets no role, so a role on the leading icon sits under the node TalkBack focuses.
 */
@Composable
private fun QueueRowMenuItem(
    @StringRes labelRes: Int,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = stringResource(labelRes)) },
        modifier = Modifier.semantics { role = Role.Button },
        enabled = enabled,
        onClick = onClick,
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    )
}

/** Wide enough for a title and its controls, narrow enough to stay one glance on a tablet. */
private val SHEET_MAX_WIDTH = 720.dp

private val LIST_MAX_HEIGHT = 420.dp

private const val LIST_MAX_HEIGHT_FRACTION = 0.6f

private val ROW_THUMB_SIZE = 56.dp

private const val NOW_PLAYING_TINT_ALPHA = 0.12f

@Preview(name = "Queue sheet", showBackground = true, widthDp = 480)
@Composable
private fun QueueSheetContentPreview() {
    JellyfinTheme {
        QueueSheetContent(
            queue =
                listOf(
                    previewTrack("t1", "Fake Plastic Trees"),
                    previewTrack("t2", "Bones"),
                    previewTrack("t3", "Nice Dream"),
                ),
            currentIndex = 1,
            onJumpTo = {},
            onRemove = {},
            onMoveUp = {},
            onMoveDown = {},
            onStop = {},
            onClose = {},
        )
    }
}

private fun previewTrack(
    id: String,
    name: String,
) = JellyfinItem(
    id = id,
    name = name,
    type = ItemType.AUDIO,
    artists = listOf("Radiohead"),
    downloadState = DownloadState.NotDownloaded,
)
