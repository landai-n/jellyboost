package dev.jellyboost.player.model

/**
 * Scrubbing thumbnails for one item, whatever their origin (docs/PLAN.md, "M9 Polish" → trickplay
 * scrubber).
 *
 * Jellyfin does not store one file per thumbnail: it stores *sprite sheets* of
 * `columns × rows` thumbnails, each thumbnail [thumbnailWidth] × [thumbnailHeight] pixels, one
 * thumbnail every [intervalMs] of video. Drawing the right frame therefore means fetching the right
 * sheet and cropping the right cell out of it, and both halves of that are pure arithmetic — which
 * is why they live here, on a value type, rather than in a composable.
 *
 * The same type describes a downloaded item (tile URIs are `file://` paths on disk, resolved by
 * `LocalTrickplay`) and a streamed one (tile URIs are server URLs built by
 * `TrickplayResolver`). That is deliberate: the scrubber has one code path, and the M8 promise that
 * the player UI is byte-identical online and offline extends to it.
 *
 * @property thumbnailWidth pixel width of a single thumbnail — also the resolution the sheets were
 *   generated at, and part of a server tile's URL.
 * @property thumbnailHeight pixel height of a single thumbnail.
 * @property columns thumbnails per row inside one sheet (the server calls this `tileWidth`).
 * @property rows rows of thumbnails inside one sheet (the server calls this `tileHeight`).
 * @property thumbnailCount total thumbnails across every sheet.
 * @property intervalMs milliseconds of video between two consecutive thumbnails.
 * @property tileUris the sheets, in tile order.
 */
data class TrickplayTiles(
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val columns: Int,
    val rows: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
) {
    /** Aspect ratio of one thumbnail, or `16 / 9` when the server's geometry is unusable. */
    val aspectRatio: Float
        get() =
            when {
                thumbnailWidth > 0 && thumbnailHeight > 0 -> thumbnailWidth.toFloat() / thumbnailHeight
                else -> DEFAULT_ASPECT_RATIO
            }

    /**
     * The sheet, and the cell inside it, holding the thumbnail for [positionMs].
     *
     * `null` when the geometry is unusable, or when the thumbnail would sit on a sheet that is not
     * available — which is what a position past the last generated thumbnail resolves to, and the
     * reason the scrubber can simply not draw a preview instead of drawing a broken one.
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
