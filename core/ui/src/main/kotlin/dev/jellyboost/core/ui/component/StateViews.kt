package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

private val SpinnerSize = 36.dp

private val SpinnerStroke = 3.dp

private val SpinnerTrack = Color.White.copy(alpha = 0.14f)

private val StateGlyphSize = 36.dp

private val StateGlyphTint = Color.White.copy(alpha = 0.45f)

private val StateMessage =
    TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

private val StatePanelMaxWidth = 420.dp

private val DashedPanelPadding = 28.dp

private val DashedBorderColor = Color.White.copy(alpha = 0.12f)

private val DashLength = 6.dp

private val DashGap = 5.dp

/**
 * The spinner keeps its label and polite live region: an unlabelled one replaces the previous
 * screen's node with nothing, and TalkBack lands on an empty page.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.state_loading)
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier =
                Modifier
                    .size(SpinnerSize)
                    .semantics {
                        contentDescription = loading
                        liveRegion = LiveRegionMode.Polite
                    },
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = SpinnerStroke,
            trackColor = SpinnerTrack,
        )
    }
}

/**
 * @param actionLabel defaults to "Retry"; a screen whose only recovery is to leave must say so,
 *   since a control is named for what it does (WCAG 2.5.3).
 * @param dashedPanel for a state view sitting *inside* other content, which otherwise reads as
 *   missing content rather than as a place content will appear.
 * @param announce see [EmptyState]; opt-in so a screen that draws its own announcement (search
 *   does) does not say it twice.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
    actionLabel: String? = null,
    dashedPanel: Boolean = false,
    announce: LiveRegionMode? = null,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) actionLabel ?: stringResource(R.string.state_retry) else null,
        onAction = onRetry,
        dashedPanel = dashedPanel,
        announce = announce,
    )
}

/**
 * @param announce `null` by default because these views also appear on a first composition, where
 *   the reader is about to read the message anyway and announcing stutters. Pass it where the view
 *   *replaces* content the user was already in.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dashedPanel: Boolean = false,
    announce: LiveRegionMode? = null,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
        dashedPanel = dashedPanel,
        announce = announce,
    )
}

@Composable
private fun MessageState(
    icon: ImageVector,
    message: String,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    dashedPanel: Boolean,
    announce: LiveRegionMode?,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Dimens.SpaceExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = StatePanelMaxWidth)
                    .then(if (dashedPanel) Modifier.dashedPanel() else Modifier)
                    .padding(if (dashedPanel) DashedPanelPadding else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StateGlyphTint,
                modifier = Modifier.size(StateGlyphSize),
            )
            Text(
                text = message,
                style = StateMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // The live region belongs on the message, not the panel: the panel would drag the
                // action button's label into the announcement as one sentence.
                modifier =
                    Modifier
                        .padding(top = Dimens.SpaceMedium)
                        .then(
                            if (announce != null) {
                                Modifier.semantics { liveRegion = announce }
                            } else {
                                Modifier
                            },
                        ),
            )
            if (actionLabel != null && onAction != null) {
                PrimaryPillButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.padding(top = Dimens.SpaceLarge),
                    small = true,
                )
            }
        }
    }
}

/** Drawn, not composed: Compose has no dashed `BorderStroke`. */
private fun Modifier.dashedPanel(): Modifier =
    this.drawBehind {
        val stroke = Stroke(width = 1.dp.toPx(), pathEffect = dashEffect(DashLength.toPx(), DashGap.toPx()))
        val inset = stroke.width / 2f
        drawRoundRect(
            color = DashedBorderColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke.width, size.height - stroke.width),
            cornerRadius = CornerRadius(Dimens.PanelRadius.toPx()),
            style = stroke,
        )
    }

private fun dashEffect(
    dash: Float,
    gap: Float,
): PathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap))

@Preview(name = "ErrorState", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 320)
@Composable
private fun ErrorStatePreview() {
    JellyfinTheme {
        ErrorState(message = "Could not reach the server.", onRetry = {})
    }
}

@Preview(
    name = "EmptyState — panel",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 420,
    heightDp = 320,
)
@Composable
private fun EmptyStatePreview() {
    JellyfinTheme {
        EmptyState(message = "Nothing here yet.", dashedPanel = true)
    }
}

@Preview(name = "LoadingState", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 200)
@Composable
private fun LoadingStatePreview() {
    JellyfinTheme {
        LoadingState()
    }
}
