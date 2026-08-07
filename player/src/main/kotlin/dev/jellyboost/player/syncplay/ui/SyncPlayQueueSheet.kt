package dev.jellyboost.player.syncplay.ui

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
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.R
import java.util.UUID

/**
 * What the group is going to watch, and the four things a member may do about it.
 *
 * A bottom sheet like [SyncPlayGroupSheet] and for the same reason — it is a panel to read, not a
 * one-tap picker — but a scrolling one: a group queue can be a whole season.
 *
 * **Nothing here changes anything locally.** Tapping a row, moving one, removing one and the
 * next/previous buttons are all requests to the server; the list redraws when the group's own
 * `PlayQueueUpdate` comes back (docs/notes/syncplay-m11-plan.md, key decision 11). Reordering is
 * therefore up/down buttons rather than a drag: a dragged row that snaps back until the server
 * answers reads as a broken gesture, and the round trip is not this device's to skip.
 *
 * The ViewModel is resolved here rather than passed in from `:app` (DECISIONS.md, 2026-07-30): this
 * is a surface inside the player screen, not a navigation destination, and the solo player's call
 * site has no business knowing about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncPlayQueueSheet(
    onDismiss: () -> Unit,
    viewModel: SyncPlayQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        QueueSheetContent(
            state = state,
            onPlay = viewModel::play,
            onMove = viewModel::move,
            onRemove = viewModel::remove,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
        )
    }
}

@Composable
private fun QueueSheetContent(
    state: SyncPlayQueueUiState,
    onPlay: (UUID) -> Unit,
    onMove: (UUID, Int) -> Unit,
    onRemove: (UUID) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    // The list's cap has to know how tall the sheet is allowed to be: a fixed 420 dp is more than a
    // phone-landscape sheet has in total, and a long queue then pushes the header and the
    // next/previous buttons off the top of it.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val listMaxHeight = queueListMaxHeight(maxHeight)

        Column(
            modifier =
                Modifier
                    // Capped and centred like the group sheet: full-bleed rows on a 2560 px tablet put a
                    // title and the buttons that act on it a hand-span apart.
                    .widthIn(max = SHEET_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(bottom = Dimens.SpaceExtraLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            QueueHeader(
                state = state,
                onNext = onNext,
                onPrevious = onPrevious,
            )

            HorizontalDivider()

            if (state.isEmpty) {
                Text(
                    text = stringResource(R.string.player_syncplay_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.SpaceLarge),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = listMaxHeight),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
            ) {
                itemsIndexed(items = state.rows, key = { _, row -> row.playlistItemId }) { index, row ->
                    QueueRow(
                        row = row,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.rows.lastIndex,
                        onPlay = { onPlay(row.playlistItemId) },
                        onMoveUp = { onMove(row.playlistItemId, index - 1) },
                        onMoveDown = { onMove(row.playlistItemId, index + 1) },
                        onRemove = { onRemove(row.playlistItemId) },
                    )
                }
            }
        }
    }
}

/**
 * How tall the queue list may grow inside a sheet that has [maxHeight] to give.
 *
 * [LIST_MAX_HEIGHT] alone was written for a landscape *tablet*, where it is a comfortable half of the
 * sheet. On a phone in landscape — the orientation the player forces — the whole sheet is about
 * 330 dp tall, so a fixed 420 dp list is taller than everything it is supposed to share the sheet
 * with, and the header and the next/previous buttons get pushed out of view. Taking at most
 * [LIST_MAX_HEIGHT_FRACTION] of what the sheet has leaves them on screen and still shows three or
 * four rows, which is enough to see what is coming and to move it.
 *
 * On any tablet the fraction lands well above [LIST_MAX_HEIGHT], so the old cap keeps winning and
 * nothing there changes. An unbounded sheet — no measured height at all — falls into the same case.
 */
internal fun queueListMaxHeight(maxHeight: Dp): Dp = minOf(LIST_MAX_HEIGHT, maxHeight * LIST_MAX_HEIGHT_FRACTION)

@Composable
private fun QueueHeader(
    state: SyncPlayQueueUiState,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.player_syncplay_queue),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = pluralStringResource(R.plurals.player_syncplay_queue_count, state.rows.size, state.rows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Next and previous belong to the *group*: they were deliberately left without a call site in
        // Phase 3, because the player has no queue of its own to move through (DECISIONS.md,
        // 2026-07-30, M11 Phase 3 note 4). Here there is one.
        IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(R.string.player_syncplay_queue_previous),
            )
        }
        IconButton(onClick = onNext, enabled = state.hasNext) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(R.string.player_syncplay_queue_next),
            )
        }
    }
}

@Composable
private fun QueueRow(
    row: SyncPlayQueueRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    // primary@12% (2026 refresh, Phase 5 sweep) — replaces `colorScheme.secondaryContainer` for
    // consistency with the rest of the refresh's "primary is the one accent" language; see spec
    // "Phase 5 sweep notes".
    val background =
        if (row.isPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = NOW_PLAYING_TINT_ALPHA)
        } else {
            Color.Transparent
        }
    val thumbShape = RoundedCornerShape(Dimens.CardCornerRadius)
    // Every row's three buttons used to announce the same three words, so a queue of ten episodes
    // was thirty identical "Move up" stops with nothing to tell them apart (audit A11Y-P-18). The
    // title is what distinguishes them, and it is already here.
    val title = row.title ?: stringResource(R.string.player_syncplay_queue_item_loading)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                .background(background)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.player_syncplay_queue_play),
                    onClick = onPlay,
                ).padding(Dimens.SpaceExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        JellyfinAsyncImage(
            url = row.imageUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .width(ROW_THUMB_WIDTH)
                    .heightIn(max = ROW_THUMB_HEIGHT)
                    .clip(thumbShape)
                    // The inner hairline every `MediaCardArtwork` draws on top of its image (spec,
                    // "Artwork") — restyled container visuals only, per the sweep's own scope note.
                    .border(GlassDefaults.HairlineWidth, GlassDefaults.ArtworkInnerHairline, thumbShape),
            contentScale = ContentScale.Crop,
        )

        QueueRowLabels(title = title, subtitle = row.subtitle, modifier = Modifier.weight(1f))

        if (row.isPlaying) {
            Icon(
                imageVector = Icons.Filled.Equalizer,
                contentDescription = stringResource(R.string.player_syncplay_queue_playing),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        QueueRowActions(
            title = title,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun QueueRowLabels(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            // Until the item is fetched the row still has to say *something*, so the queue's
            // shape is readable before the last round trip lands.
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Reorder and remove.
 *
 * @param title every row's three buttons used to announce the same three words, so a queue of ten
 *   episodes was thirty identical "Move up" stops with nothing to tell them apart (audit A11Y-P-18).
 */
@Composable
private fun QueueRowActions(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = stringResource(R.string.player_syncplay_queue_move_up_item, title),
        )
    }
    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
        Icon(
            imageVector = Icons.Outlined.ArrowDownward,
            contentDescription = stringResource(R.string.player_syncplay_queue_move_down_item, title),
        )
    }
    IconButton(onClick = onRemove) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.player_syncplay_queue_remove_item, title),
        )
    }
}

/** Wide enough for a title and its four controls, narrow enough to stay one glance on a tablet. */
private val SHEET_MAX_WIDTH = 720.dp

/** Caps the list so the sheet never grows past a comfortable half of a landscape tablet. */
private val LIST_MAX_HEIGHT = 420.dp

/** How much of a *short* sheet the list may take before the header stops fitting above it. */
private const val LIST_MAX_HEIGHT_FRACTION = 0.6f

private val ROW_THUMB_WIDTH = 96.dp
private val ROW_THUMB_HEIGHT = 54.dp

/** Now-playing row tint (spec, "Phase 5 sweep notes": "now-playing row tint → primary@12%"). */
private const val NOW_PLAYING_TINT_ALPHA = 0.12f

@Preview(name = "Queue sheet", showBackground = true, widthDp = 800)
@Composable
private fun QueueSheetContentPreview() {
    QueueSheetPreviewContent()
}

/**
 * The same sheet with only a phone-landscape sheet's worth of height, where the list has to give way
 * to the header rather than the other way round.
 */
@Preview(name = "Queue sheet · phone landscape", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun QueueSheetContentPhoneLandscapePreview() {
    QueueSheetPreviewContent()
}

@Composable
private fun QueueSheetPreviewContent() {
    JellyfinTheme {
        QueueSheetContent(
            state =
                SyncPlayQueueUiState(
                    rows =
                        listOf(
                            previewRow("The Original", "S1 E1", isPlaying = true),
                            previewRow("Chestnut", "S1 E2", isPlaying = false),
                            previewRow(null, null, isPlaying = false),
                        ),
                    playingIndex = 0,
                ),
            onPlay = {},
            onMove = { _, _ -> },
            onRemove = {},
            onNext = {},
            onPrevious = {},
        )
    }
}

private fun previewRow(
    title: String?,
    subtitle: String?,
    isPlaying: Boolean,
) = SyncPlayQueueRow(
    playlistItemId = UUID.randomUUID(),
    itemId = UUID.randomUUID(),
    title = title,
    subtitle = subtitle,
    imageUrl = null,
    isPlaying = isPlaying,
)
