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
 * It was written twice, byte for byte, which is how the audit's duplication cluster starts; hoisted
 * here so the copy and the button order can only ever drift together.
 *
 * The hairline this file used to spell out belongs to `:core:ui`'s [ConfirmDialog] now. The KDoc
 * here claimed the app's dialog idiom "can only ever drift together" while three dialogs elsewhere
 * were already drawing plain M3 chrome — exactly the drift it was written to rule out (audit
 * 2026-08-08, DUP-2). One composable owns the edge for all ten of them, so the claim is now true of
 * the app rather than of these two.
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
