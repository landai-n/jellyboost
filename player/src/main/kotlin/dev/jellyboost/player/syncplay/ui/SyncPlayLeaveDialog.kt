package dev.jellyboost.player.syncplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.player.R

/** Shared by [SyncPlayGroupSheet] and the groups screen: keep the copy in one place. */
@Composable
internal fun LeaveGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.player_syncplay_leave_title),
        text = stringResource(R.string.player_syncplay_leave_body),
        confirmLabel = stringResource(R.string.player_syncplay_leave),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
