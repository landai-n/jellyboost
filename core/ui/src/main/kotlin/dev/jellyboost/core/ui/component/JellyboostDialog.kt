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
 * Use this rather than [AlertDialog] directly: M3's flat `surfaceContainerHigh` container has no
 * edge against the video surfaces, glass headers and poster grids this app's dialogs land on.
 *
 * @param modifier chained *after* the border, so a caller can size or position the dialog without
 *   replacing that edge.
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
 * @param confirmLabel what the *action* is called, never "OK" — a button is named for what it does.
 * @param dismissLabel pass one only where "cancel" is ambiguous: the *Cancel all* confirmation
 *   answers with "Keep", since "Cancel" there could mean either button.
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
