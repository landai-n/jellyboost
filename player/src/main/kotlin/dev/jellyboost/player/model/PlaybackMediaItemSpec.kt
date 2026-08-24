package dev.jellyboost.player.model

/**
 * Must stay free of `MediaItem`/`Uri`: `android.net.Uri` is a throwing stub in local unit tests, and
 * that is what keeps `ExoMediaSourceFactory`'s decision table testable without an emulator.
 *
 * @property audioSidecars merged as their own sources, since `MediaItem` has no audio analogue of
 *   `SubtitleConfiguration`. **Element `i` becomes merge child `i + 1`** (child 0 is the main
 *   source) — the positional contract `TrackSelectionController` navigates back by.
 */
internal data class PlaybackMediaItemSpec(
    /** `MediaItem.mediaId`; the Jellyfin item id, used to correlate player callbacks. */
    val mediaId: String,
    val uri: String,
    /** Forced MIME type — set for HLS, where the URL extension does not identify the format. */
    val mimeType: String? = null,
    val subtitles: List<SubtitleSpec> = emptyList(),
    val audioSidecars: List<AudioSidecarSpec> = emptyList(),
)

/**
 * Carries no id, unlike [SubtitleSpec]: a merge cannot name a child's tracks, so the bridge back to
 * Jellyfin is the child's *position* (`TrackSelectionController.selectAudio`).
 *
 * @property streamIndex the absolute Jellyfin `MediaStream.index`, so merge order can be checked
 *   against the track list it was built from.
 */
internal data class AudioSidecarSpec(
    val streamIndex: Int,
    val uri: String,
)

/**
 * [id] must carry the [EXTERNAL_SUBTITLE_ID_PREFIX] convention: it is how `TrackSelectionController`
 * maps an ExoPlayer text track back onto its Jellyfin stream index.
 */
internal data class SubtitleSpec(
    val id: String,
    val uri: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

internal const val EXTERNAL_SUBTITLE_ID_PREFIX: String = "external:"

internal fun externalSubtitleTrackId(index: Int): String = "$EXTERNAL_SUBTITLE_ID_PREFIX$index"

/**
 * The id given to a `SubtitleConfiguration` is **not** the id the player reports back:
 * `MergingMediaPeriod.onPrepared` rebuilds each child format as `childIndex + ":" + format.id`, so
 * `external:2` arrives as `1:external:2`. An item merged twice (audio sidecars *and* subtitles)
 * arrives as `0:1:external:2`, which is why the strip is a loop rather than one step.
 */
internal fun jellyfinIndexOfTrackId(trackId: String?): Int? {
    val id = trackId?.withoutMergePrefixes() ?: return null
    if (!id.startsWith(EXTERNAL_SUBTITLE_ID_PREFIX)) return null
    return id.removePrefix(EXTERNAL_SUBTITLE_ID_PREFIX).toIntOrNull()
}

/**
 * Strips only leading digit runs followed by `:` — the shape a merge adds, never our own non-numeric
 * prefix. Safe for Matroska's numeric track ids: `0:0:2` reduces to `2`, still "none of ours".
 */
private fun String.withoutMergePrefixes(): String {
    var id = this
    var separator = id.indexOf(':')
    while (separator > 0 && id.substring(0, separator).all(Char::isDigit)) {
        id = id.substring(separator + 1)
        separator = id.indexOf(':')
    }
    return id
}
