package dev.jellyboost.core.ui.component

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.GlassDefaults

/**
 * The app's dialog: an M3 [AlertDialog] wearing the hairline the 2026 refresh gives every panel.
 *
 * `AlertDialog` draws a flat `surfaceContainerHigh` container with no outline of its own, and this
 * app's dialogs land over a video surface, a glass header or a poster grid — surfaces a flat
 * container has no edge against. Every dialog in the app therefore spelled out the same two
 * arguments by hand: a `border` at [GlassDefaults.PanelHairline] on `shapes.extraLarge`, and
 * `containerColor = surface`.
 *
 * Hand-spelled at seven sites and **missing at three** (the detail screen's delete confirmation and
 * the downloads screen's delete and cancel-all confirmations), which is what an idiom kept by
 * convention costs: `SyncPlayLeaveDialog`'s KDoc claimed the hairline "can only ever drift together"
 * while three dialogs elsewhere had already drifted off it entirely (audit 2026-08-08, DUP-2). The
 * three now match the other seven, which is a deliberate visual change — see DECISIONS.md
 * 2026-08-08.
 *
 * The signature is `AlertDialog`'s, minus the two parameters this owns, so a caller that needs a
 * styled title or an arbitrary body ([PlayerDisplayDialog], the Quick Connect dialog, the SyncPlay
 * create-group dialog) keeps every slot it had. [ConfirmDialog] is the shorthand for the common
 * case: a sentence, a confirm and a cancel.
 *
 * @param modifier chained *after* the border, so a caller can still position or size the dialog
 *   without replacing the edge that makes it one of this app's.
 */
@Composable
fun JellyboostAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier =
            Modifier
                .border(
                    width = GlassDefaults.HairlineWidth,
                    color = GlassDefaults.PanelHairline,
                    shape = MaterialTheme.shapes.extraLarge,
                ).then(modifier),
        dismissButton = dismissButton,
        title = title,
        text = text,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * "Are you sure?" — a title, one sentence, and the two buttons that answer it.
 *
 * Six of the app's ten dialogs are exactly this shape (sign out, switch storage location, delete a
 * download from the detail screen and from the downloads screen, cancel the whole queue, leave a
 * SyncPlay group), and each had spelled out its own `TextButton`s around its own copy. The order —
 * dismiss at the start, confirm at the end — is `AlertDialog`'s own and is now settled in one place
 * rather than six.
 *
 * @param confirmLabel what the *action* is called, never "OK": the button says what it does, which
 *   is the difference between a user reading the sentence and a user reading the buttons.
 * @param dismissLabel defaults to the app-wide [R.string.action_cancel] (audit DUP-6). A caller
 *   passes its own only where "cancel" would be ambiguous — the downloads screen's *Cancel all*
 *   confirmation answers with "Keep", because "Cancel" there could mean either button.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirmLabel) }
        },
        modifier = modifier,
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = dismissLabel) }
        },
        title = { Text(text = title) },
        text = { Text(text = text) },
    )
}
