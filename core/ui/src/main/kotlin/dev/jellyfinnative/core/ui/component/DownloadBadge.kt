package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme

/**
 * The one visual marker that distinguishes downloaded media from streamed media.
 *
 * Rendered in the corner of every item card (docs/PLAN.md, "Screens"). [DownloadState.NotDownloaded]
 * renders nothing at all, so callers can pass the state unconditionally.
 */
@Composable
fun DownloadBadge(
    state: DownloadState,
    modifier: Modifier = Modifier,
) {
    if (state is DownloadState.NotDownloaded) return

    Box(
        modifier =
            modifier
                .size(Dimens.BadgeSize + Dimens.SpaceExtraSmall)
                .background(color = Color.Black.copy(alpha = 0.6f), shape = CircleShape)
                .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DownloadState.Downloaded ->
                Icon(
                    imageVector = Icons.Filled.DownloadForOffline,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                )

            is DownloadState.Queued ->
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = "Queued for download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            is DownloadState.Paused ->
                Icon(
                    imageVector = Icons.Filled.PauseCircleFilled,
                    contentDescription = "Download paused",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            is DownloadState.Failed ->
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Download failed",
                    tint = MaterialTheme.colorScheme.error,
                )

            is DownloadState.Downloading ->
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(Dimens.BadgeSize),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )

            is DownloadState.NotDownloaded -> Unit
        }
    }
}

@Preview(name = "Download badges", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun DownloadBadgePreview() {
    JellyfinTheme {
        Box(modifier = Modifier.padding(Dimens.SpaceMedium)) {
            DownloadBadge(state = DownloadState.Downloaded)
        }
    }
}

@Preview(name = "Download badge — in progress", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun DownloadBadgeProgressPreview() {
    JellyfinTheme {
        Box(modifier = Modifier.padding(Dimens.SpaceMedium)) {
            DownloadBadge(state = DownloadState.Downloading(progress = 0.4f))
        }
    }
}
