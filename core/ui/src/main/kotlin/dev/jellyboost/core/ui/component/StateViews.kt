package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/** Centred spinner shown while a screen loads its first page of data. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Full-screen failure state with an optional retry.
 *
 * Callers pass a message already translated from `AppError`, so `:core:ui` never has to know the
 * failure taxonomy.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) "Retry" else null,
        onAction = onRetry,
    )
}

/** Full-screen "nothing here yet" state. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
private fun MessageState(
    icon: ImageVector,
    message: String,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Dimens.SpaceExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.SpaceMedium),
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = Dimens.SpaceLarge),
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Preview(name = "ErrorState", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 320)
@Composable
private fun ErrorStatePreview() {
    JellyfinTheme {
        ErrorState(message = "Could not reach the server.", onRetry = {})
    }
}

@Preview(name = "EmptyState", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 320)
@Composable
private fun EmptyStatePreview() {
    JellyfinTheme {
        EmptyState(message = "Nothing here yet.")
    }
}
