package dev.jellyboost.data.mapper

import kotlin.math.ceil

/**
 * Jellyfin resizes server-side, so the width in the URL decides the bytes on the wire, the bytes
 * Coil caches, and the JPEG decoded on every memory-cache miss while a grid is flung.
 *
 * Widths are the largest **dp** a surface draws at (the `*_DP` constants) times device density,
 * snapped to [BUCKETS] so densities do not each carve out an entry in the server's resized-image
 * cache and so a URL stays stable across app versions, keeping Coil's disk cache warm.
 *
 * Not the widths `:data:downloads` requests: artwork saved beside a download is written once and
 * must survive being moved to another device, so it is sized generously and independently.
 */
internal data class ArtworkRequestWidths(
    val poster: Int,
    val thumb: Int,
    val backdrop: Int,
) {
    companion object {
        /**
         * Widest a 2:3 poster is drawn: `Dimens.PosterWidth` is 120dp and the grid's
         * `Adaptive(110.dp)` columns settle at ~126dp on a tablet in portrait.
         */
        const val POSTER_DP = 128

        /** Widest a 16:9 thumb card is ever drawn: `Dimens.ThumbWidth` (210dp), rounded up. */
        const val THUMB_DP = 224

        /**
         * Backdrops are full-width but sit behind a scrim, cropped to a 220dp band. Three quarters of
         * a large tablet's width is indistinguishable there and costs a third fewer pixels.
         */
        const val BACKDROP_DP = 512

        /** Coarse at the top; fine where posters and thumbs land, since a wide bucket is wasted decode. */
        private val BUCKETS = intArrayOf(160, 240, 320, 400, 480, 560, 640, 800, 960, 1280, 1600, 1920)

        /** @return [widthDp] in pixels, rounded up to a [BUCKETS] entry and capped at the largest. */
        fun requestWidth(
            widthDp: Int,
            density: Float,
        ): Int {
            require(widthDp > 0) { "widthDp must be > 0, was $widthDp" }
            require(density > 0f) { "density must be > 0, was $density" }
            val needed = ceil(widthDp * density.toDouble()).toInt()
            return BUCKETS.firstOrNull { it >= needed } ?: BUCKETS.last()
        }

        fun forDensity(density: Float): ArtworkRequestWidths =
            ArtworkRequestWidths(
                poster = requestWidth(POSTER_DP, density),
                thumb = requestWidth(THUMB_DP, density),
                backdrop = requestWidth(BACKDROP_DP, density),
            )

        /** For callers with no display to measure. `2.0` is the xhdpi baseline. */
        val Default: ArtworkRequestWidths = forDensity(density = 2f)
    }
}
