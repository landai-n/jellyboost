package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

private val BadgeContainerSize = 24.dp

private val BadgeScrim = Color.Black.copy(alpha = 0.65f)

private val BadgeGlyphSize = 14.dp

private val RingSize = 20.dp

private val RingStroke = 2.dp

private const val RING_TRACK_ALPHA = 0.25f

private val WaitingGlyphTint = Color.White.copy(alpha = 0.75f)

/**
 * [DownloadState.NotDownloaded] draws nothing, so callers pass the state unconditionally.
 *
 * The finished state must not become a tick: on a card that mark already means "watched".
 *
 * @param decorative `true` inside a node that already names the download state (an item card). A
 *   standalone badge keeps its label, including the in-flight ring — the one state with no words.
 */
@Composable
fun DownloadBadge(
    state: DownloadState,
    modifier: Modifier = Modifier,
    decorative: Boolean = false,
) {
    if (state is DownloadState.NotDownloaded) return
    val label = downloadStateLabel(state).takeUnless { decorative }

    Box(
        modifier =
            modifier
                .size(BadgeContainerSize)
                .background(color = BadgeScrim, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DownloadState.Downloaded ->
                BadgeGlyph(
                    icon = Icons.Filled.DownloadForOffline,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                )

            is DownloadState.Queued ->
                BadgeGlyph(
                    icon = Icons.Filled.Schedule,
                    contentDescription = label,
                    tint = WaitingGlyphTint,
                )

            is DownloadState.Paused ->
                BadgeGlyph(
                    icon = Icons.Filled.Pause,
                    contentDescription = label,
                    tint = WaitingGlyphTint,
                )

            is DownloadState.Failed ->
                BadgeGlyph(
                    icon = Icons.Filled.ErrorOutline,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.error,
                )

            is DownloadState.Downloading ->
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier =
                        Modifier
                            .size(RingSize)
                            // The ring has no glyph to hang a description off, so it takes one with
                            // the percentage in it.
                            .semantics { label?.let { contentDescription = it } },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = RING_TRACK_ALPHA),
                    strokeWidth = RingStroke,
                    // A closed ring: the default track gap reads as a rendering glitch at 20dp.
                    gapSize = 0.dp,
                )

            is DownloadState.NotDownloaded -> Unit
        }
    }
}

@Composable
private fun BadgeGlyph(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(BadgeGlyphSize),
    )
}

@Preview(name = "Download badges", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun DownloadBadgePreview() {
    JellyfinTheme {
        Row(
            modifier = Modifier.padding(Dimens.SpaceMedium),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            DownloadBadge(state = DownloadState.Downloaded)
            DownloadBadge(state = DownloadState.Queued)
            DownloadBadge(state = DownloadState.Paused)
            DownloadBadge(state = DownloadState.Failed)
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
