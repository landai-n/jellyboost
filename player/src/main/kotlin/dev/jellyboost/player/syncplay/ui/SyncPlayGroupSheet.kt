package dev.jellyboost.player.syncplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.PillChip
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
        containerColor = MaterialTheme.colorScheme.surface,
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
        GroupParticipants(groupName = state.groupName, participants = state.participants)

        HorizontalDivider()

        GroupPlaybackToggles(
            isShuffled = state.isShuffled,
            repeatMode = state.repeatMode,
            onSetShuffle = onSetShuffle,
            onSetRepeat = onSetRepeat,
        )

        HorizontalDivider()

        GhostPillButton(
            text = stringResource(R.string.player_syncplay_leave),
            onClick = { confirmingLeave = true },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.AutoMirrored.Filled.Logout,
        )
    }

    if (confirmingLeave) {
        LeaveGroupDialog(
            onDismiss = { confirmingLeave = false },
            onConfirm = {
                confirmingLeave = false
                onLeave()
            },
        )
    }
}

/** Who is in the group: the name, the count, then one row per participant. */
@Composable
private fun ColumnScope.GroupParticipants(
    groupName: String,
    participants: List<String>,
) {
    Text(text = groupName, style = MaterialTheme.typography.titleLarge)
    Text(
        text =
            pluralStringResource(
                R.plurals.player_syncplay_participants,
                participants.size,
                participants.size,
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    participants.forEach { name ->
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
}

/** The two group-wide playback settings: shuffle, and the repeat mode. */
@Composable
private fun ColumnScope.GroupPlaybackToggles(
    isShuffled: Boolean,
    repeatMode: SyncPlayRepeatMode,
    onSetShuffle: (Boolean) -> Unit,
    onSetRepeat: (SyncPlayRepeatMode) -> Unit,
) {
    // The settings rows' pattern: the whole row is the switch — one node, "Shuffle queue, on", the
    // full width as its target — and the control inside it is inert so it contributes nothing of
    // its own. An unlabelled Switch beside a Text is two stops, the second of them nameless.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .toggleable(
                    value = isShuffled,
                    role = Role.Switch,
                    onValueChange = onSetShuffle,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_syncplay_shuffle),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = isShuffled, onCheckedChange = null)
    }

    Text(
        text = stringResource(R.string.player_syncplay_repeat),
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        SyncPlayRepeatMode.entries.forEach { mode ->
            PillChip(
                text = stringResource(mode.labelRes()),
                selected = mode == repeatMode,
                onClick = { onSetRepeat(mode) },
            )
        }
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
