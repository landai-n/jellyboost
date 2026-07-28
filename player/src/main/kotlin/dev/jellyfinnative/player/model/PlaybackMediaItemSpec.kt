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

/** The Jellyfin stream index behind an ExoPlayer track id, or `null` if it is not one of ours. */
fun jellyfinIndexOfTrackId(trackId: String?): Int? =
    trackId?.removePrefix(EXTERNAL_SUBTITLE_ID_PREFIX)?.takeIf { it != trackId }?.toIntOrNull()
