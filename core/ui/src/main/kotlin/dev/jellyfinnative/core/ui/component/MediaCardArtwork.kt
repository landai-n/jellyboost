package dev.jellyfinnative.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.ui.R
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
 * How every selectable row or card reacts to touch: tap does whatever the caller says, and a long
 * press — when the caller offers one — buzzes and enters batch-selection mode.
 *
 * The haptic is here rather than in the two screens because it is a property of the gesture, not of
 * the list: a long press that selects something with no tactile confirmation reads as a press that
 * did nothing until the eye finds the bar that appeared at the other end of the screen.
 */
@Composable
fun Modifier.selectableCardClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.combinedClickable(
        onClick = onClick,
        onLongClick =
            onLongClick?.let { longClick ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
    )
}

/**
 * Shared artwork block behind [PosterCard] and [ThumbCard]: the image itself plus the overlays
 * every card carries — the resume progress bar, the watched tick, the download badge, and (while a
 * list is in batch-selection mode) the selection scrim and indicator.
 *
 * @param selected `null` when the list is **not** in selection mode, which is the ordinary case and
 *   draws nothing extra; `false`/`true` put the card in the mode's unselected/selected state. One
 *   nullable flag rather than a pair of booleans because "selected while not selectable" is not a
 *   state that exists, and a pair would let a caller express it.
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
    selected: Boolean? = null,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    JellyfinAsyncImage(
        url = imageUrl,
        contentDescription = contentDescription,
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .then(
                    if (selected == true) {
                        Modifier.border(SELECTED_BORDER_WIDTH, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                ).clip(shape),
        contentScale = ContentScale.Crop,
        placeholderIcon = placeholderIcon,
        overlay = {
            CardOverlays(
                downloadState = downloadState,
                played = played,
                progress = progress,
                selected = selected,
            )
        },
    )
}

@Composable
private fun BoxScope.CardOverlays(
    downloadState: DownloadState,
    played: Boolean,
    progress: Float?,
    selected: Boolean?,
) {
    if (selected == true) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_SCRIM_ALPHA)),
        )
    }

    DownloadBadge(
        state = downloadState,
        modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.SpaceExtraSmall),
    )

    // The selection indicator takes the watched tick's corner and, while the mode is on, its place:
    // both are a check, and two checks on one card would be a puzzle rather than two facts. The
    // tick comes back the moment selection mode ends.
    if (selected != null) {
        SelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopStart))
    } else if (played && progress == null) {
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

/**
 * The filled / hollow circle a card shows in selection mode.
 *
 * Both states are drawn, not just the selected one: an unselected card in a selection-mode grid has
 * to say that it *could* be selected, otherwise the mode looks like it applies to one card only.
 */
@Composable
internal fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription =
            stringResource(
                if (selected) R.string.selection_item_selected else R.string.selection_item_not_selected,
            ),
        tint =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = UNSELECTED_INDICATOR_ALPHA)
            },
        modifier =
            modifier
                .padding(Dimens.SpaceExtraSmall)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = INDICATOR_BACKDROP_ALPHA))
                .padding(2.dp),
    )
}

private val SELECTED_BORDER_WIDTH = 2.dp

private const val SELECTED_SCRIM_ALPHA = 0.35f

private const val UNSELECTED_INDICATOR_ALPHA = 0.85f

/** Keeps both indicator states legible over bright artwork. */
private const val INDICATOR_BACKDROP_ALPHA = 0.45f
