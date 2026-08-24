package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * Centre-cropped whatever the source ratio: callers' URLs fall back to a 2:3 poster, a realistic
 * source for a wide header.
 *
 * @param title `null` for the detail screen, which draws its own headline below the backdrop.
 */
@Composable
fun BackdropHeader(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    height: Dp = Dimens.BackdropHeight,
    actions: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height),
    ) {
        JellyfinAsyncImage(
            url = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            placeholderIcon = Icons.Outlined.Movie,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(JellyfinGradients.BackdropScrim),
        )

        if (title != null) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Dimens.ScreenPadding),
            ) {
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.W700,
                            letterSpacing = (-0.02).em,
                        ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = CardSubtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        actions()
    }
}

@Preview(name = "BackdropHeader", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420)
@Composable
private fun BackdropHeaderPreview() {
    JellyfinTheme {
        BackdropHeader(
            imageUrl = null,
            title = "Westworld",
            subtitle = "2016 · TV-MA · 4 seasons",
        )
    }
}
