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
 * Jellyfin serves trickplay as sheets of `columns × rows` thumbnails, so one cell is reached by drawing the
 * whole sheet and sliding it under a clipping window. Deliberately not a bitmap transformation: the sheet then
 * stays one cache entry every neighbouring thumbnail hits, so a drag decodes nothing until the next sheet.
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
    // Keyed on the sheet URI, which every cell of a sheet shares: the scrubber recomposes at pointer rate
    // during a drag, and rebuilding the request re-parses the URL twice for the cache keys each time.
    val request =
        remember(context, thumbnail.uri) {
            // Cache keys must be set explicitly and token-stripped: Coil's default key is the request data,
            // and `thumbnail.uri` carries the access token, so a fresh token would orphan every cached tile —
            // and keeping the token off disk would rest on Coil internals rather than on a key we control.
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

/** Strips [ApiClient.QUERY_ACCESS_TOKEN]; a no-op for a downloaded item's `file://` tile, which never had one. */
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

/** Fixed, not derived from the window: sheets are generated at 320 px wide, and drawing larger magnifies blur. */
internal val TRICKPLAY_PREVIEW_HEIGHT = 84.dp

private val PREVIEW_CORNER = 6.dp
