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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme

/**
 * Full-bleed backdrop with a scrim — the top of every detail screen, and the hero slot on the home
 * screen.
 *
 * The scrim fades into the app background so the header blends into the scrolling content instead
 * of ending on a hard edge.
 *
 * The artwork is always centre-cropped to fill the box regardless of its source aspect ratio: the
 * URL callers pass falls back through backdrop → thumb → primary image, and a 2:3 poster is a
 * realistic fallback for a wide header.
 *
 * @param title drawn over the backdrop when non-null. Optional because the item detail screen
 *   already renders the headline in its content below the backdrop (`DetailFacts`) — drawing it
 *   here too duplicated the title. Left in for callers (e.g. the home screen hero) that have no
 *   other place to put it.
 * @param actions optional trailing content (Play / Download / Favourite) drawn under the title.
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
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
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
