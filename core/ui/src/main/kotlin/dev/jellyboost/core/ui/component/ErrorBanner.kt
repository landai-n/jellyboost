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
import androidx.compose.runtime.ReadOnlyComposable
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
import dev.jellyboost.core.ui.theme.LocalIsLightTheme

/**
 * Lightened rather than `colorScheme.error` itself: that colour is tuned to be read as a fill, and
 * at 13sp on a 10%-alpha wash of itself it fails a contrast check.
 */
internal val ErrorBannerDarkContent: Color = Color(0xFFF0A3AE)

/**
 * The lightening exists only because the dark scheme's wash is nearly black. On a light page the
 * same 10% wash is a pale pink and the light scheme's own `error` reads 5.19:1 on it, so the banner
 * says its message in the colour it is about — a lightened red there would be the unreadable one.
 */
val ErrorBannerContent: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalIsLightTheme.current) MaterialTheme.colorScheme.error else ErrorBannerDarkContent

private const val BANNER_FILL_ALPHA = 0.10f

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
 * For a failure *within* still-usable content, unlike [ErrorState]; it never offers a retry of its
 * own, since the screen it sits in already has one.
 *
 * One node, marked assertive — the one live-region level that interrupts, which is right for a user
 * mid-flow about to retype a password into a form that already rejected it.
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
