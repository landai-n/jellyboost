package dev.jellyboost.feature.music.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.feature.music.R

/**
 * The music queue, as a bottom sheet: current track highlighted, tap to jump, up/down to reorder,
 * a remove button per row (M13 Phase 4, docs/notes/music-m13-plan.md).
 *
 * Modelled on `SyncPlayQueueSheet` (`:player`), the codebase's one prior queue-sheet — same header
 * shape, same row shape, same up/down reorder buttons rather than a drag. That sheet chose buttons
 * because its reordering is a request to the server that only takes effect when the group's next
 * `PlayQueueUpdate` answers, and a dragged row snapping back until that round trip returns reads as
 * a broken gesture. The music queue's [dev.jellyboost.core.common.music.MusicController.moveItem]
 * is a local, synchronous list move with no such round trip — but no drag-to-reorder pattern exists
 * anywhere in this codebase to mirror (searched: no `detectDragGestures`/reorderable usage), and
 * inventing one for this one sheet is out of scope for what Phase 4 asks for. The up/down buttons
 * are shipped instead, satisfying "reorder" without a new interaction pattern; a real drag is a
 * fair follow-up once a second call site wants it.
 *
 * Unlike `SyncPlayQueueSheet`, this one does **not** resolve its own ViewModel: it is drawn from
 * inside [dev.jellyboost.feature.music.nowplaying.NowPlayingScreen], which already collects
 * everything this sheet needs from [dev.jellyboost.feature.music.nowplaying.NowPlayingViewModel] —
 * a second collector of the same `@Singleton` controller state would be redundant. `SyncPlayQueueSheet`
 * resolves its own because it is opened from `:player`'s solo `PlayerScreen`, which has no other
 * access to the group's queue.
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
            QueueHeader(count = queue.size, onClose = onClose)

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

@Composable
private fun QueueHeader(
    count: Int,
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
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.music_now_playing_queue_close),
            )
        }
    }
}

/**
 * The queue's rows, with no header or dismissal of their own — the part [QueueSheetContent] and
 * [dev.jellyboost.feature.music.nowplaying.NowPlayingScreen]'s wide two-pane layout both need.
 *
 * The wide layout shows the queue inline rather than behind [QueueSheet] (docs/notes/music-m13-plan.md,
 * Phase 4: "≥560dp: two-pane — artwork left, controls + queue list right"), so factoring the list
 * out is what lets both call sites draw the exact same rows instead of drifting apart.
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

/** The row's leading artwork — the same hairline-bordered square the rest of the sheet's art uses. */
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

/** Title over subtitle — the row's one flexible column, so the caller passes its `weight`. */
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

/** Reorder up/down and remove — the trailing controls every queue row carries. */
@Composable
private fun QueueRowActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = stringResource(R.string.music_now_playing_queue_move_up),
        )
    }
    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
        Icon(
            imageVector = Icons.Outlined.ArrowDownward,
            contentDescription = stringResource(R.string.music_now_playing_queue_move_down),
        )
    }
    IconButton(onClick = onRemove) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.music_now_playing_queue_remove),
        )
    }
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
