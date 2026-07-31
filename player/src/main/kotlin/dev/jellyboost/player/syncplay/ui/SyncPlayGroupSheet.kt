package dev.jellyboost.player.syncplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.player.R
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.ui.PlayerSyncPlayState

/**
 * Who you are watching with, and the three things a member can change about the group.
 *
 * A bottom sheet where the player's own pickers are dialogs, because it is not a picker: it is a
 * panel — participants, shuffle, repeat, leave — read while playback is paused or waiting, rather
 * than a one-tap list that must not cover the seek bar. It follows `LibraryFilterSheet`, which is
 * the same shape.
 *
 * Every toggle is a *request*: shuffle and repeat belong to the group, so the switch reflects what
 * the server last said, not what was tapped, and it settles when the `PlayQueueUpdate` comes back.
 * Leaving is behind a confirmation because it is not undoable in one tap — rejoining means going to
 * the groups screen — and because in landscape this sheet sits under the user's thumbs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncPlayGroupSheet(
    state: PlayerSyncPlayState,
    onSetShuffle: (Boolean) -> Unit,
    onSetRepeat: (SyncPlayRepeatMode) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        GroupSheetContent(
            state = state,
            onSetShuffle = onSetShuffle,
            onSetRepeat = onSetRepeat,
            onLeave = onLeave,
        )
    }
}

@Composable
private fun GroupSheetContent(
    state: PlayerSyncPlayState,
    onSetShuffle: (Boolean) -> Unit,
    onSetRepeat: (SyncPlayRepeatMode) -> Unit,
    onLeave: () -> Unit,
) {
    var confirmingLeave by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                // Capped and centred for the same reason the control bar is: full-bleed rows on a
                // 2560 px tablet put the label and its switch a hand-span apart.
                .widthIn(max = SHEET_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(text = state.groupName, style = MaterialTheme.typography.titleLarge)
        Text(
            text =
                pluralStringResource(
                    R.plurals.player_syncplay_participants,
                    state.participants.size,
                    state.participants.size,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.participants.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = Dimens.SpaceMedium),
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_syncplay_shuffle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.isShuffled, onCheckedChange = onSetShuffle)
        }

        Text(
            text = stringResource(R.string.player_syncplay_repeat),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
            SyncPlayRepeatMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == state.repeatMode,
                    onClick = { onSetRepeat(mode) },
                    label = { Text(text = stringResource(mode.labelRes())) },
                )
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = { confirmingLeave = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Text(
                text = stringResource(R.string.player_syncplay_leave),
                modifier = Modifier.padding(start = Dimens.SpaceSmall),
            )
        }
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(text = stringResource(R.string.player_syncplay_leave_title)) },
            text = { Text(text = stringResource(R.string.player_syncplay_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingLeave = false
                        onLeave()
                    },
                ) { Text(text = stringResource(R.string.player_syncplay_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text(text = stringResource(R.string.player_syncplay_cancel))
                }
            },
        )
    }
}

private fun SyncPlayRepeatMode.labelRes(): Int =
    when (this) {
        SyncPlayRepeatMode.None -> R.string.player_syncplay_repeat_none
        SyncPlayRepeatMode.One -> R.string.player_syncplay_repeat_one
        SyncPlayRepeatMode.All -> R.string.player_syncplay_repeat_all
    }

/** Wide enough for a participant list, narrow enough to stay one glance on a tablet. */
private val SHEET_MAX_WIDTH = 640.dp

@Preview(name = "Group sheet", showBackground = true, widthDp = 700)
@Composable
private fun GroupSheetContentPreview() {
    JellyfinTheme {
        GroupSheetContent(
            state =
                PlayerSyncPlayState(
                    inGroup = true,
                    groupName = "Film night",
                    participants = listOf("casey", "alex", "sam"),
                    queueSize = 3,
                    hasQueue = true,
                    isShuffled = true,
                    repeatMode = SyncPlayRepeatMode.All,
                ),
            onSetShuffle = {},
            onSetRepeat = {},
            onLeave = {},
        )
    }
}
