package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.popShadow

/** Near-solid rather than glass: blurring what is behind a message only makes it harder to read. */
private val SnackbarContainer = Color(color = 0xFF202020).copy(alpha = 0.92f)

private val SnackbarHorizontalPadding = 18.dp

private val SnackbarVerticalPadding = 14.dp

private val SnackbarMaxWidth = 520.dp

private val SnackbarLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

private val SnackbarActionLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        lineHeight = 18.sp,
    )

private val ActionStartGap = 6.dp

private val ActionPadding = 8.dp

/**
 * The action must keep calling [SnackbarData.performAction]: that is what resumes the host's
 * suspended `showSnackbar` with `ActionPerformed`.
 */
@Composable
fun PillSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val actionLabel = snackbarData.visuals.actionLabel

    Row(
        modifier =
            modifier
                .padding(Dimens.SpaceMedium)
                .widthIn(max = SnackbarMaxWidth)
                .popShadow(CircleShape)
                .clip(CircleShape)
                .background(color = SnackbarContainer, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .padding(horizontal = SnackbarHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = snackbarData.visuals.message,
            style = SnackbarLabel,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = SNACKBAR_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            // Vertical padding lives on the message, not the row, so the action can claim a full
            // 48dp of height without stacking on top of it.
            modifier =
                Modifier
                    .weight(weight = 1f, fill = false)
                    .padding(vertical = SnackbarVerticalPadding),
        )
        if (actionLabel != null) {
            Box(
                modifier =
                    Modifier
                        .padding(start = ActionStartGap)
                        .heightIn(min = Dimens.MinTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { snackbarData.performAction() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    style = SnackbarActionLabel,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = ActionPadding),
                )
            }
        }
    }
}

private const val SNACKBAR_MAX_LINES = 2

/** The one piece of [SnackbarData] a preview needs. */
private class PreviewSnackbarData(
    message: String,
    actionLabel: String? = null,
) : SnackbarData {
    override val visuals: SnackbarVisuals =
        object : SnackbarVisuals {
            override val actionLabel: String? = actionLabel
            override val duration: SnackbarDuration = SnackbarDuration.Short
            override val message: String = message
            override val withDismissAction: Boolean = false
        }

    override fun dismiss() = Unit

    override fun performAction() = Unit
}

@Preview(name = "PillSnackbar", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun PillSnackbarPreview() {
    JellyfinTheme {
        PillSnackbar(snackbarData = PreviewSnackbarData(message = "Added 3 to downloads"))
    }
}

@Preview(name = "PillSnackbar action", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun PillSnackbarActionPreview() {
    JellyfinTheme {
        PillSnackbar(
            snackbarData =
                PreviewSnackbarData(
                    message = "Server unreachable — showing downloads",
                    actionLabel = "Retry",
                ),
        )
    }
}
