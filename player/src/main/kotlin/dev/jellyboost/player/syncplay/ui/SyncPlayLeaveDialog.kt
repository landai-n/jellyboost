package dev.jellyboost.player.syncplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.player.R

/**
 * "Leave this group?" — the confirmation both SyncPlay surfaces put in front of the one destructive
 * action they offer: the in-player group sheet ([SyncPlayGroupSheet]) and the groups screen
 * ([SyncPlayGroupsContent]).
 *
 * Hoisted here so the copy and the button order can only ever drift together, rather than written
 * out once per surface.
 *
 * The dialog's own edge belongs to `:core:ui`'s [ConfirmDialog], which owns it for every dialog in
 * the app — so the chrome cannot drift between them either.
 */
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
