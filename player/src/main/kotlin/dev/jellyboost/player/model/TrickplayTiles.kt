package dev.jellyboost.player.model

/**
 * Jellyfin stores scrubbing thumbnails as sprite sheets of `columns × rows` cells, so drawing a frame
 * means fetching the right sheet and cropping the right cell. Tile URIs are `file://` paths for a
 * downloaded item and server URLs for a streamed one; the scrubber has one code path either way.
 *
 * @property thumbnailWidth pixels, and also the resolution the sheets were generated at — part of a
 *   server tile's URL.
 * @property columns thumbnails per row inside one sheet (the server calls this `tileWidth`).
 * @property rows rows inside one sheet (the server calls this `tileHeight`).
 * @property intervalMs milliseconds of video between two consecutive thumbnails.
 * @property tileUris the sheets, in tile order.
 */
internal data class TrickplayTiles(
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val columns: Int,
    val rows: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
) {
    /** Falls back to 16:9 when the server's geometry is unusable. */
    val aspectRatio: Float
        get() =
            when {
                thumbnailWidth > 0 && thumbnailHeight > 0 -> thumbnailWidth.toFloat() / thumbnailHeight
                else -> DEFAULT_ASPECT_RATIO
            }

    /**
     * `null` when the geometry is unusable or the sheet is missing (a position past the last
     * generated thumbnail): callers draw no preview rather than a broken one.
     */
    fun tileFor(positionMs: Long): TrickplayThumbnail? {
        val perTile = columns * rows
        if (intervalMs <= 0 || perTile <= 0 || thumbnailCount <= 0) return null

        val thumbnail = (positionMs.coerceAtLeast(0L) / intervalMs).toInt().coerceAtMost(thumbnailCount - 1)
        val uri = tileUris.getOrNull(thumbnail / perTile) ?: return null
        val withinTile = thumbnail % perTile
        return TrickplayThumbnail(
            uri = uri,
            column = withinTile % columns,
            row = withinTile / columns,
        )
    }

    private companion object {
        const val DEFAULT_ASPECT_RATIO = 16f / 9f
    }
}
