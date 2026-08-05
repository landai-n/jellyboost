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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.R
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme

/** Diameter and stroke of the spinner a whole screen waits behind. */
private val SpinnerSize = 36.dp

private val SpinnerStroke = 3.dp

/** Track behind the spinner's head — visible enough to read as a ring, faint enough to recede. */
private val SpinnerTrack = Color.White.copy(alpha = 0.14f)

private val StateGlyphSize = 36.dp

/** Tint of a state view's glyph: present, but subordinate to the message under it. */
private val StateGlyphTint = Color.White.copy(alpha = 0.45f)

private val StateMessage =
    TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

/** Widest a state message gets before it wraps — a full-width line of 13sp text is hard to scan. */
private val StatePanelMaxWidth = 420.dp

private val DashedPanelPadding = 28.dp

private val DashedBorderColor = Color.White.copy(alpha = 0.12f)

private val DashLength = 6.dp

private val DashGap = 5.dp

/** Centred spinner shown while a screen loads its first page of data. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SpinnerSize),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = SpinnerStroke,
            trackColor = SpinnerTrack,
        )
    }
}

/**
 * Full-screen failure state with an optional retry.
 *
 * Callers pass a message already translated from `AppError`, so `:core:ui` never has to know the
 * failure taxonomy.
 *
 * @param actionLabel what the button says. Defaults to "Retry", which is what [onRetry] means
 *   almost everywhere — but not everywhere: a screen whose only recovery is to leave (the player's
 *   error state closes the player) has to say so, because a control has to be named for what it
 *   does (WCAG 2.5.3; accessibility audit 2026-08-05). Ignored when [onRetry] is `null`, since
 *   there is no button to label.
 * @param dashedPanel draws the message inside a dashed outline. Off by default: a state view that
 *   fills a screen needs no container, but one that sits *inside* other content (an empty tab under
 *   a header, a failed section of a populated screen) does, or it reads as content that is missing
 *   rather than as a place where content will appear.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
    actionLabel: String? = null,
    dashedPanel: Boolean = false,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) actionLabel ?: stringResource(R.string.state_retry) else null,
        onAction = onRetry,
        dashedPanel = dashedPanel,
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
    dashedPanel: Boolean = false,
) {
    MessageState(
        icon = icon,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
        dashedPanel = dashedPanel,
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
                modifier = Modifier.padding(top = Dimens.SpaceMedium),
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

/**
 * The dashed outline of an empty container.
 *
 * Drawn rather than composed: Compose has no dashed `BorderStroke`, and `drawBehind` with a
 * `PathEffect` is both the shortest way to say it and the one that costs no extra layout node.
 */
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
