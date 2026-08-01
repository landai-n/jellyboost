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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/** Diameter of the dark disc every download state is drawn on. */
private val BadgeContainerSize = 24.dp

/** The disc itself — dark enough to carry a glyph over the brightest artwork. */
private val BadgeScrim = Color.Black.copy(alpha = 0.65f)

private val BadgeGlyphSize = 14.dp

/** The progress ring, inset inside the disc. */
private val RingSize = 20.dp

private val RingStroke = 2.dp

/** How much of the ring's colour the not-yet-downloaded remainder keeps. */
private const val RING_TRACK_ALPHA = 0.25f

/** Tint of the waiting states — present, but not competing with the artwork. */
private val WaitingGlyphTint = Color.White.copy(alpha = 0.75f)

/**
 * The one visual marker that distinguishes downloaded media from streamed media.
 *
 * Rendered in the corner of every item card (docs/PLAN.md, "Screens"). [DownloadState.NotDownloaded]
 * renders nothing at all, so callers can pass the state unconditionally.
 *
 * The finished state keeps the `DownloadForOffline` glyph rather than becoming the mocks' solid disc
 * with a tick: on a card that tick is already taken — it is what "watched" means — and two identical
 * marks in the same corner meaning two different things is worse than one mark that differs from a
 * mock (design spec, "Download badge component states").
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
                .size(BadgeContainerSize)
                .background(color = BadgeScrim, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DownloadState.Downloaded ->
                BadgeGlyph(
                    icon = Icons.Filled.DownloadForOffline,
                    contentDescription = stringResource(R.string.badge_downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                )

            is DownloadState.Queued ->
                BadgeGlyph(
                    icon = Icons.Filled.Schedule,
                    contentDescription = stringResource(R.string.badge_download_queued),
                    tint = WaitingGlyphTint,
                )

            is DownloadState.Paused ->
                BadgeGlyph(
                    icon = Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.badge_download_paused),
                    tint = WaitingGlyphTint,
                )

            is DownloadState.Failed ->
                BadgeGlyph(
                    icon = Icons.Filled.ErrorOutline,
                    contentDescription = stringResource(R.string.badge_download_failed),
                    tint = MaterialTheme.colorScheme.error,
                )

            is DownloadState.Downloading ->
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(RingSize),
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
    contentDescription: String,
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
