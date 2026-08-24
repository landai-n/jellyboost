package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * Text and glyph colour of an error banner.
 *
 * A lightened `#CF6679` rather than `colorScheme.error` itself: the palette's error colour is tuned
 * to be read as a *fill* (a failed progress bar, a destructive button), and at 13sp on a 10%-alpha
 * wash of itself it sits too close to the background to pass a contrast check. This is the same hue
 * pushed up in lightness until the sentence is comfortably readable, which is what the mocks
 * specify (`#F0A3AE`).
 */
val ErrorBannerContent: Color = Color(0xFFF0A3AE)

/** The wash behind the message — the palette's error colour, faint enough to stay a background. */
private const val BANNER_FILL_ALPHA = 0.10f

/** Its edge, strong enough to hold the shape where the fill fades into the page. */
private const val BANNER_BORDER_ALPHA = 0.28f

private val BannerHorizontalPadding = 16.dp

private val BannerVerticalPadding = 14.dp

private val BannerIconSize = 18.dp

private val BannerLabel =
    TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

/**
 * An inline failure notice: something the user tried did not work, and the screen is still usable.
 *
 * Distinct from [ErrorState], which takes over a screen that has nothing to show. A banner sits
 * *within* working content — a lost session above a login form, a refresh that failed above the
 * stale list it failed to replace — so it never offers a retry of its own; the screen it belongs to
 * already has one.
 *
 * It announces itself. A banner appears *because* something the user just did failed, and a failure
 * nobody is told about is a screen that silently did nothing — so the whole banner is one node,
 * marked assertive, which is the one live-region level that interrupts. Assertive rather than
 * polite precisely because the user is mid-flow: they are
 * about to retype a password into a form that has already rejected it.
 *
 * @param message already translated by the caller; `:core:ui` never sees the failure taxonomy.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Outlined.ErrorOutline,
) {
    val shape = RoundedCornerShape(Dimens.CardCornerRadius)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                .background(
                    color = MaterialTheme.colorScheme.error.copy(alpha = BANNER_FILL_ALPHA),
                    shape = shape,
                ).border(
                    width = GlassDefaults.HairlineWidth,
                    color = MaterialTheme.colorScheme.error.copy(alpha = BANNER_BORDER_ALPHA),
                    shape = shape,
                ).padding(horizontal = BannerHorizontalPadding, vertical = BannerVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ErrorBannerContent,
                modifier = Modifier.size(BannerIconSize),
            )
        }
        Text(
            text = message,
            style = BannerLabel,
            color = ErrorBannerContent,
        )
    }
}

@Preview(name = "ErrorBanner", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun ErrorBannerPreview() {
    JellyfinTheme {
        ErrorBanner(
            message = "Your session expired. Sign in again to keep watching.",
            modifier = Modifier.padding(Dimens.SpaceLarge),
        )
    }
}
