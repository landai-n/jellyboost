package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.ui.theme.Dimens

/**
 * How every card in the design system reads its `width` parameter.
 *
 * A concrete [Dp] pins the card, which is what a horizontally scrolling row needs. [Dp.Unspecified]
 * fills whatever the parent offers, which is what a `GridCells.Adaptive` cell needs — and it does
 * so by measurement rather than by wrapping each cell in a `BoxWithConstraints`, i.e. without one
 * subcomposition per visible card while the grid scrolls.
 */
internal fun Modifier.cardWidth(width: Dp): Modifier = if (width.isSpecified) this.width(width) else this.fillMaxWidth()

/**
 * Shared artwork block behind [PosterCard] and [ThumbCard]: the image itself plus the three
 * overlays every card carries — the resume progress bar, the watched tick, and the download badge.
 */
@Composable
internal fun MediaCardArtwork(
    imageUrl: String?,
    contentDescription: String?,
    aspectRatio: Float,
    downloadState: DownloadState,
    played: Boolean,
    progress: Float?,
    placeholderIcon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    JellyfinAsyncImage(
        url = imageUrl,
        contentDescription = contentDescription,
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
        contentScale = ContentScale.Crop,
        placeholderIcon = placeholderIcon,
        overlay = {
            CardOverlays(downloadState = downloadState, played = played, progress = progress)
        },
    )
}

@Composable
private fun BoxScope.CardOverlays(
    downloadState: DownloadState,
    played: Boolean,
    progress: Float?,
) {
    DownloadBadge(
        state = downloadState,
        modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.SpaceExtraSmall),
    )

    if (played && progress == null) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Dimens.SpaceExtraSmall)
                    .clip(RoundedCornerShape(percent = 50)),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Watched",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(2.dp),
            )
        }
    }

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Black.copy(alpha = 0.5f),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}
