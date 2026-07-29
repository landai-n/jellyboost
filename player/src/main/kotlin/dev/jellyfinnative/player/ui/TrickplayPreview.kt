package dev.jellyfinnative.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jellyfinnative.player.model.TrickplayThumbnail
import dev.jellyfinnative.player.model.TrickplayTiles

/**
 * One scrubbing thumbnail, cropped out of the sprite sheet it lives on.
 *
 * Jellyfin serves trickplay as sheets of `columns × rows` thumbnails, so there is no URL for "the
 * frame at 23 minutes" — there is a URL for the sheet that contains it and a cell to cut out. This
 * draws the *whole* sheet at `columns × rows` preview-sized cells and slides it under a
 * preview-sized clipping window, which puts the wanted cell in view. Compare a bitmap transformation
 * (jellyfin-android's `SubsetTransformation`): the same picture reaches the screen, but the sheet
 * stays in Coil's cache as one entry that every neighbouring thumbnail hits, so dragging along the
 * seek bar decodes nothing until the drag crosses into the next sheet.
 *
 * The arithmetic that picks the cell is [TrickplayTiles.tileFor] and is unit-tested there; this
 * composable only turns a column and a row into an offset.
 */
@Composable
internal fun TrickplayPreview(
    tiles: TrickplayTiles,
    thumbnail: TrickplayThumbnail,
    modifier: Modifier = Modifier,
    height: Dp = TRICKPLAY_PREVIEW_HEIGHT,
) {
    val width = height * tiles.aspectRatio

    Box(
        modifier =
            modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .background(Color.Black),
    ) {
        AsyncImage(
            // Every cell of a sheet resolves to the same URL, so this is a cache hit for all but
            // the first thumbnail of each sheet — no request, no decode, no flicker.
            model = thumbnail.uri,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .size(width = width * tiles.columns, height = height * tiles.rows)
                    .offset(x = -width * thumbnail.column, y = -height * thumbnail.row),
        )
    }
}

/**
 * Preview height.
 *
 * Fixed in dp rather than derived from the window: the sheets are generated at a fixed pixel size
 * (320 px wide by default), and drawing them much larger on a tablet only magnifies the blur.
 */
internal val TRICKPLAY_PREVIEW_HEIGHT = 84.dp

private val PREVIEW_CORNER = 6.dp
