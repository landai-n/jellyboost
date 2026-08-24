package dev.jellyboost.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.jellyboost.player.model.TrickplayThumbnail
import dev.jellyboost.player.model.TrickplayTiles
import org.jellyfin.sdk.api.client.ApiClient

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
    val context = LocalPlatformContext.current
    // Remembered per sheet, not rebuilt per composition: the enclosing scrubber recomposes at
    // pointer rate during a drag, and rebuilding the request — re-parsing the URL twice for the
    // cache keys each time — is pure allocation churn on the one interaction where the frame
    // budget is visibly tight. Keyed on the sheet URI because every cell of a sheet
    // shares it; a drag only builds a new request when it crosses into the next sheet.
    val request =
        remember(context, thumbnail.uri) {
            // The cache keys are set explicitly, token-stripped: `thumbnail.uri` carries the access
            // token as a query parameter (the only URL in the app that does — trickplay is fetched
            // by Coil, which cannot ride `JellyfinAuthInterceptor`'s header). Coil's default cache
            // key is the request's data, token and all, which means re-logging in — a fresh token —
            // silently orphans every tile this item had ever cached, and the fact that today's token
            // never reaches disk rests on undocumented internals of how Coil derives a *disk* key
            // from that default, not on a key this app controls. Stripping the token here is a
            // no-op for a downloaded item's `file://` URIs, which never carried one.
            val cacheKey = thumbnail.uri.withoutAccessToken()
            ImageRequest
                .Builder(context)
                .data(thumbnail.uri)
                .diskCacheKey(cacheKey)
                .memoryCacheKey(cacheKey)
                .build()
        }

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
            model = request,
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
 * [uri] with the trickplay access-token query parameter removed.
 *
 * A no-op for a URL with no query string at all (a downloaded item's `file://` tile) and for any
 * URL that never carried the token to begin with; the one caller that matters is the server-built
 * sheet URL `TrickplayResolver`/`SdkStreamUrlFactory` appends [ApiClient.QUERY_ACCESS_TOKEN] to.
 */
internal fun String.withoutAccessToken(): String {
    val queryStart = indexOf('?')
    if (queryStart < 0) return this

    val base = substring(0, queryStart)
    val kept =
        substring(queryStart + 1)
            .split('&')
            .filterNot { it.startsWith("${ApiClient.QUERY_ACCESS_TOKEN}=") }

    return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
}

/**
 * Preview height.
 *
 * Fixed in dp rather than derived from the window: the sheets are generated at a fixed pixel size
 * (320 px wide by default), and drawing them much larger on a tablet only magnifies the blur.
 */
internal val TRICKPLAY_PREVIEW_HEIGHT = 84.dp

private val PREVIEW_CORNER = 6.dp
