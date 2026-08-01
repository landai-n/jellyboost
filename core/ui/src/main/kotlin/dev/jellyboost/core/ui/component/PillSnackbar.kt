package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * Container of the snackbar pill — the surface colour, held just off opaque.
 *
 * A near-solid fill rather than glass: a snackbar is the one surface that appears over content the
 * user was already reading, and blurring what is behind a message makes the message harder to read
 * rather than the background prettier.
 */
private val SnackbarContainer = Color(color = 0xFF202020).copy(alpha = 0.92f)

private val SnackbarHorizontalPadding = 18.dp

private val SnackbarVerticalPadding = 14.dp

/** Keeps a long message from spanning a tablet's full width, where it would be unreadable. */
private val SnackbarMaxWidth = 520.dp

private val SnackbarLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

/**
 * The refresh's snackbar: a floating pill with the message and nothing else.
 *
 * Deliberately has no action slot. Every snackbar the app shows today is a *report* — "Marked 4
 * watched", "Added 3 to downloads" — and the refresh drops the Undo affordance the mocks never
 * had, so [SnackbarData.visuals]'s `actionLabel` is not rendered. A future snackbar that genuinely
 * needs a button wants a different component, not a slot here that is empty everywhere.
 *
 * Pass it to a `SnackbarHost`: `SnackbarHost(hostState) { data -> PillSnackbar(data) }`.
 */
@Composable
fun PillSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .padding(Dimens.SpaceMedium)
                .widthIn(max = SnackbarMaxWidth)
                .popShadow(CircleShape)
                .clip(CircleShape)
                .background(color = SnackbarContainer, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .padding(horizontal = SnackbarHorizontalPadding, vertical = SnackbarVerticalPadding),
    ) {
        Text(
            text = snackbarData.visuals.message,
            style = SnackbarLabel,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = SNACKBAR_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SNACKBAR_MAX_LINES = 2

/** The one piece of [SnackbarData] a preview needs: a message and two callbacks that do nothing. */
private class PreviewSnackbarData(
    message: String,
) : SnackbarData {
    override val visuals: SnackbarVisuals =
        object : SnackbarVisuals {
            override val actionLabel: String? = null
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
