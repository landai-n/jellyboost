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
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * **Nothing here changes anything locally**: every action is a server request, and the list redraws when the
 * group's `PlayQueueUpdate` comes back. Hence up/down buttons rather than a drag that would snap back.
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
    // The list cap needs the sheet's own height; see [queueListMaxHeight].
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val listMaxHeight = queueListMaxHeight(maxHeight)

        Column(
            modifier =
                Modifier
                    // Full-bleed rows on a 2560 px tablet put a title and the buttons acting on it a hand-span apart.
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
 * A phone-landscape sheet is about 330 dp tall in total, so the fixed [LIST_MAX_HEIGHT] alone would push the
 * header and the next/previous buttons out of view. On a tablet the fraction lands above it and it still wins.
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
    // primary@12%, not `secondaryContainer`: primary is the app's one accent.
    val background =
        if (row.isPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = NOW_PLAYING_TINT_ALPHA)
        } else {
            Color.Transparent
        }
    val thumbShape = RoundedCornerShape(Dimens.CardCornerRadius)
    val title = row.title ?: stringResource(R.string.player_syncplay_queue_item_loading)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardCornerRadius))
                .background(background)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(CoreUiR.string.action_play),
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

/** @param title distinguishes this row's three buttons from every other row's, which otherwise all speak alike. */
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

private val SHEET_MAX_WIDTH = 720.dp

/** Half a landscape tablet's sheet. */
private val LIST_MAX_HEIGHT = 420.dp

/** How much of a *short* sheet the list may take before the header stops fitting above it. */
private const val LIST_MAX_HEIGHT_FRACTION = 0.6f

private val ROW_THUMB_WIDTH = 96.dp
private val ROW_THUMB_HEIGHT = 54.dp

private const val NOW_PLAYING_TINT_ALPHA = 0.12f

@Preview(name = "Queue sheet", showBackground = true, widthDp = 800)
@Composable
private fun QueueSheetContentPreview() {
    QueueSheetPreviewContent()
}

/** Phone-landscape height, where the list must give way to the header. */
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
