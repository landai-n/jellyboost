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
 * The sheet's title, its track count, and the two verbs that act on the queue as a whole.
 *
 * Stop comes *before* Close, in the order the two are destructive: ending the session is the one
 * that throws the queue away, and putting it last would put a queue-destroying button where the
 * muscle memory for "close this sheet" already is.
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

/**
 * Reorder up/down and remove, behind **one** overflow button.
 *
 * They were three always-visible 48dp `IconButton`s — 144dp of it, on top of the row's 56dp artwork
 * and the current track's equalizer glyph. The title column is the row's only flexible child, so it
 * absorbed all of that: on a phone-width sheet every single title ellipsised after a couple of words
 * (device walk, 2026-08-15). One overflow returns 96dp to the titles, which are what the sheet
 * exists to show; the three verbs keep their exact strings and their edge-disabled states, one tap
 * further in.
 *
 * The icon is tinted explicitly rather than inheriting `LocalContentColor`. [QueueList] is drawn in
 * two places — inside this sheet, where a `ModalBottomSheet`'s own `Surface` provides `onSurface`,
 * and inline in `NowPlayingScreen`'s wide right-hand pane, which has no `Surface` ancestor at all
 * and therefore inherits Material's *bare* default for that local, `Color.Black`. On the app's
 * `#101010` background that rendered these controls invisible (device walk, 2026-08-15).
 * [GlassIconTint] is the token every other icon button in this module already names.
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

    // The menu has to be a sibling of the button *inside its own Box*: a DropdownMenu anchors to its
    // layout parent, and without the wrapper that parent is the whole row — which would drop the
    // menu from the artwork's edge instead of from this button (`AppActions.AppOverflowMenu`).
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                // No "more options" string exists in this module and none may be added here, so the
                // sheet's own title stands in: "Queue" is at least the subject of every verb behind
                // the button.
                contentDescription = stringResource(R.string.music_now_playing_queue),
                tint = GlassIconTint,
            )
        }

        // `LibrarySortMenu`'s dressing — the app's one prior menu on a dark surface: the theme's
        // `surface` rather than M3's `surfaceContainer`, and the panel hairline that gives every
        // floating surface in the refresh its edge.
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
 * One entry in [QueueRowActions]' menu.
 *
 * `Role.Button` is declared on the item itself rather than on its icon: `DropdownMenuItem`'s own
 * `clickable` merges its descendants and sets no role of its own, so a role on the leading icon
 * would sit under the node TalkBack focuses (accessibility audit 2026-08-05, ROLE-01).
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
