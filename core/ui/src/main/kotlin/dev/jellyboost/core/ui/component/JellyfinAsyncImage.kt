package dev.jellyboost.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.pageInk

/** One size everywhere, from card to backdrop. */
private val PlaceholderGlyphSize = 30.dp

/** Quieter than `onSurfaceVariant`: a placeholder reads as absence, not as a picture. */
private val PlaceholderGlyphTint: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.35f)

/**
 * Takes an already-built URL: assembling them is the data layer's job, so `:core:ui` stays free of
 * Jellyfin API knowledge. A `null` or blank [url] draws the gradient placeholder.
 *
 * @param alignment where Coil anchors the source image before [contentScale] applies — matters once
 *   the box's aspect ratio differs from the artwork's (a 2:3 poster in a wide backdrop slot).
 */
@Composable
fun JellyfinAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholderIcon: ImageVector? = Icons.Outlined.Movie,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .background(JellyfinGradients.ImagePlaceholder)
                // The label belongs to the *slot*, not the bitmap: without it a cast rail or queue
                // row with no following text has nothing at all to announce.
                .then(
                    if (url.isNullOrBlank() && contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            if (placeholderIcon != null) {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = null,
                    tint = PlaceholderGlyphTint,
                    modifier = Modifier.size(PlaceholderGlyphSize),
                )
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
            )
        }
        overlay()
    }
}
