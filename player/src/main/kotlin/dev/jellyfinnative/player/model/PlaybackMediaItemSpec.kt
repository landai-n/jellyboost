package dev.jellyfinnative.player.model

/**
 * A pure description of what to hand ExoPlayer: one stream URL plus any side-loaded subtitles.
 *
 * Deliberately free of `MediaItem`/`Uri`: `android.net.Uri` is a throwing stub in local unit
 * tests, so keeping URL selection in plain data is what makes `ExoMediaSourceFactory`'s decision
 * table — the riskiest logic in this milestone — testable without an emulator. The conversion to
 * a real `MediaItem` is a single mechanical step performed on-device.
 */
data class PlaybackMediaItemSpec(
    /** `MediaItem.mediaId`; the Jellyfin item id, used to correlate player callbacks. */
    val mediaId: String,
    val uri: String,
    /** Forced MIME type — set for HLS, where the URL extension does not identify the format. */
    val mimeType: String? = null,
    val subtitles: List<SubtitleSpec> = emptyList(),
)

/**
 * One side-loaded subtitle track.
 *
 * [id] carries the [EXTERNAL_SUBTITLE_ID_PREFIX] convention so that `TrackSelectionController`
 * can map an ExoPlayer text track back onto the Jellyfin stream index it came from.
 */
data class SubtitleSpec(
    val id: String,
    val uri: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

/** Prefix of the ExoPlayer track id given to a side-loaded Jellyfin subtitle stream. */
const val EXTERNAL_SUBTITLE_ID_PREFIX: String = "external:"

/** The ExoPlayer track id for the Jellyfin subtitle stream at [index]. */
fun externalSubtitleTrackId(index: Int): String = "$EXTERNAL_SUBTITLE_ID_PREFIX$index"

/**
 * The Jellyfin stream index behind an ExoPlayer track id, or `null` if it is not one of ours.
 *
 * The id given to a `MediaItem.SubtitleConfiguration` is **not** the id the player reports back.
 * Side-loading even one subtitle makes the player a `MergingMediaSource`, and
 * `MergingMediaPeriod.onPrepared` rebuilds every format of every child as
 * `setId(childIndex + ":" + format.id)` before publishing the merged track groups — so
 * `external:2` comes back as `1:external:2`. Reading the id without allowing for that prefix
 * matched nothing, which is how a downloaded sidecar ended up refused as "not in the downloaded
 * file" (docs/notes/offline-multitrack-design.md, phase 1).
 */
fun jellyfinIndexOfTrackId(trackId: String?): Int? {
    val id = trackId?.withoutMergePrefix() ?: return null
    if (!id.startsWith(EXTERNAL_SUBTITLE_ID_PREFIX)) return null
    return id.removePrefix(EXTERNAL_SUBTITLE_ID_PREFIX).toIntOrNull()
}

/**
 * The id as its own source published it, with any `MergingMediaPeriod` child prefix removed.
 *
 * Only a leading run of digits followed by `:` is stripped, and only once: that is exactly the shape
 * the merge adds, and it cannot be confused with our own prefix, which is not numeric.
 */
private fun String.withoutMergePrefix(): String {
    val separator = indexOf(':')
    if (separator <= 0) return this
    val prefix = substring(0, separator)
    return if (prefix.all(Char::isDigit)) substring(separator + 1) else this
}
