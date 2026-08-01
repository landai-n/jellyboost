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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jellyboost.core.ui.theme.JellyfinGradients

/** Size of the glyph on the placeholder gradient — one size everywhere, from card to backdrop. */
private val PlaceholderGlyphSize = 30.dp

/**
 * Its tint: quieter than `onSurfaceVariant`, because a placeholder should read as *absence* rather
 * than as a picture of a film reel.
 */
private val PlaceholderGlyphTint = Color.White.copy(alpha = 0.35f)

/**
 * Every remote image in the app goes through this composable.
 *
 * It takes an already-built URL string: assembling image URLs (server base URL, image tags,
 * requested size) is the data layer's job, so `:core:ui` stays free of any Jellyfin API knowledge
 * (docs/PLAN.md, ":core:ui").
 *
 * When [url] is `null` or blank — the server has no artwork for the item — a gradient placeholder
 * with [placeholderIcon] is drawn instead, so rows never collapse into empty holes.
 *
 * @param url fully-qualified image URL, or `null` when the item has no artwork.
 * @param contentDescription accessibility label; `null` for purely decorative artwork.
 * @param placeholderIcon icon drawn on the placeholder gradient; `null` draws the bare gradient.
 * @param alignment where Coil anchors the source image inside the box before [contentScale] is
 *   applied — matters once the box's aspect ratio differs from the artwork's, e.g. a 2:3 poster
 *   landing in a wide backdrop slot.
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
        modifier = modifier.background(JellyfinGradients.ImagePlaceholder),
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
