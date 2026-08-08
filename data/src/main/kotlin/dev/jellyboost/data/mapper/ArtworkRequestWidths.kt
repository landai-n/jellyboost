package dev.jellyboost.data.mapper

import kotlin.math.ceil

/**
 * The pixel widths this device asks the server to scale artwork to.
 *
 * Jellyfin resizes server-side, so the width in the URL decides three things at once: the bytes on
 * the wire, the bytes Coil's disk cache holds, and the size of the JPEG that has to be decoded
 * again on every memory-cache miss while a grid is flung. Requesting artwork at the size it is
 * actually drawn at means the cached bytes *are* the display-resolution thumbnail — nothing is
 * downscaled on the way to the screen.
 *
 * The widths are derived from one knob per surface: the largest **dp** width that surface ever
 * draws the artwork at (see the `*_DP` constants), scaled by the device's density. Values are
 * snapped to [BUCKETS] so a handful of densities do not each carve out their own entry in the
 * server's resized-image cache — and so that the URL for a given item is stable across app
 * versions, which is what keeps Coil's disk cache warm across an upgrade.
 *
 * Not to be confused with the widths `:data:downloads` requests: artwork saved next to a download
 * is written once and has to survive being moved to another device, so it is deliberately sized
 * generously and independently of whatever screen happens to be attached today.
 */
internal data class ArtworkRequestWidths(
    val poster: Int,
    val thumb: Int,
    val backdrop: Int,
) {
    companion object {
        /**
         * Widest a 2:3 poster is ever drawn: the home rows pin cards at
         * `Dimens.PosterWidth` (120dp) and the library grid's `Adaptive(110.dp)` columns settle at
         * ~126dp on a tablet in portrait — narrower on a phone and in landscape, where more
         * columns fit.
         */
        const val POSTER_DP = 128

        /** Widest a 16:9 thumb card is ever drawn: `Dimens.ThumbWidth` (210dp), rounded up. */
        const val THUMB_DP = 224

        /**
         * Detail-header backdrops are drawn full-width, but they sit behind a scrim, are cropped to
         * a 220dp band and never carry detail the eye tracks. Three quarters of a large tablet's
         * width is indistinguishable there and costs a third fewer pixels than matching it exactly.
         */
        const val BACKDROP_DP = 512

        /**
         * Request widths are snapped up to one of these.
         *
         * Coarse at the top (nobody can tell 1600 from 1750 behind a scrim), fine in the range
         * posters and thumbs land in, where a bucket too wide is a bucket of wasted decode.
         */
        private val BUCKETS = intArrayOf(160, 240, 320, 400, 480, 560, 640, 800, 960, 1280, 1600, 1920)

        /**
         * @param widthDp largest width, in dp, that the surface draws this artwork at.
         * @param density the device's display density (`DisplayMetrics.density`).
         * @return the pixel width to put in the image URL: [widthDp] in pixels, rounded up to the
         *   next [BUCKETS] entry, and capped at the largest bucket.
         */
        fun requestWidth(
            widthDp: Int,
            density: Float,
        ): Int {
            require(widthDp > 0) { "widthDp must be > 0, was $widthDp" }
            require(density > 0f) { "density must be > 0, was $density" }
            val needed = ceil(widthDp * density.toDouble()).toInt()
            return BUCKETS.firstOrNull { it >= needed } ?: BUCKETS.last()
        }

        /** The widths to use on a display of the given [density]. */
        fun forDensity(density: Float): ArtworkRequestWidths =
            ArtworkRequestWidths(
                poster = requestWidth(POSTER_DP, density),
                thumb = requestWidth(THUMB_DP, density),
                backdrop = requestWidth(BACKDROP_DP, density),
            )

        /**
         * Fallback for callers with no display to measure — unit tests, and the mappers'
         * constructor default. `2.0` is the xhdpi baseline, mid-range of the densities v1 targets.
         */
        val Default: ArtworkRequestWidths = forDensity(density = 2f)
    }
}
