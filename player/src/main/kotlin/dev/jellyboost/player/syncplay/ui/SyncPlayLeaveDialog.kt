package dev.jellyboost.player.syncplay.ui

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.player.R

/**
 * "Leave this group?" — the confirmation both SyncPlay surfaces put in front of the one destructive
 * action they offer: the in-player group sheet ([SyncPlayGroupSheet]) and the groups screen
 * ([SyncPlayGroupsContent]).
 *
 * It was written twice, byte for byte, which is how the audit's duplication cluster starts; hoisted
 * here so the copy, the hairline and the button order can only ever drift together. The hairline
 * border is the app's dialog idiom — `AlertDialog` draws no outline of its own, and these two sit
 * over a video surface and a glass header respectively, where a flat container has no edge.
 */
@Composable
internal fun LeaveGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.border(
                width = GlassDefaults.HairlineWidth,
                color = GlassDefaults.PanelHairline,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = stringResource(R.string.player_syncplay_leave_title)) },
        text = { Text(text = stringResource(R.string.player_syncplay_leave_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.player_syncplay_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_syncplay_cancel))
            }
        },
    )
}
