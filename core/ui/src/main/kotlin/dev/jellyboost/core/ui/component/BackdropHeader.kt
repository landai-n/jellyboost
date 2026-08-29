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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.OverMedia
import dev.jellyboost.core.ui.theme.backdropScrim

/**
 * Centre-cropped whatever the source ratio: callers' URLs fall back to a 2:3 poster, a realistic
 * source for a wide header.
 *
 * @param title `null` for the detail screen, which draws its own headline below the backdrop.
 * @param dissolvesIntoPage `true` where the page's own content is drawn *across* the artwork's
 *   bottom edge (the wide detail stage's poster and facts column): scheme ink over that seam needs
 *   the page colour under it, so the scrim fades to the page ([JellyfinGradients.StageScrim]).
 *   Left `false`, the artwork ends on a hard edge and stays dark-scrimmed in both schemes, which is
 *   why anything this draws on it is [OverMedia]'s ink rather than the scheme's.
 * @param copyZone how far above the artwork's foot the lockup drawn on it reaches — see
 *   [Modifier.backdropScrim]. Ignored when [dissolvesIntoPage], which carries no over-media copy.
 */
@Composable
fun BackdropHeader(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    height: Dp = Dimens.BackdropHeight,
    dissolvesIntoPage: Boolean = false,
    copyZone: Dp = 0.dp,
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

        val scrim =
            if (dissolvesIntoPage) {
                Modifier.background(JellyfinGradients.StageScrim)
            } else {
                Modifier.backdropScrim(copyZone = copyZone)
            }
        Box(modifier = Modifier.fillMaxSize().then(scrim))

        if (title != null) {
            BackdropTitle(
                title = title,
                subtitle = subtitle,
                dissolvesIntoPage = dissolvesIntoPage,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Dimens.ScreenPadding),
            )
        }

        actions()
    }
}

/** Drawn on the artwork's foot, so its ink follows the scrim rather than the scheme. */
@Composable
private fun BackdropTitle(
    title: String,
    subtitle: String?,
    dissolvesIntoPage: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.02).em,
                ),
            color = if (dissolvesIntoPage) MaterialTheme.colorScheme.onBackground else OverMedia.Title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = CardSubtitleStyle,
                color =
                    if (dissolvesIntoPage) MaterialTheme.colorScheme.onSurfaceVariant else OverMedia.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
