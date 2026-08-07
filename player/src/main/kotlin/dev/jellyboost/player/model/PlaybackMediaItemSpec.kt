package dev.jellyboost.player.model

/**
 * A pure description of what to hand ExoPlayer: one stream URL plus any side-loaded tracks.
 *
 * Deliberately free of `MediaItem`/`Uri`: `android.net.Uri` is a throwing stub in local unit
 * tests, so keeping URL selection in plain data is what makes `ExoMediaSourceFactory`'s decision
 * table — the riskiest logic in this milestone — testable without an emulator. The conversion to
 * a real `MediaItem` is a single mechanical step performed on-device.
 *
 * @property audioSidecars audio tracks that live in their own files and have to be merged alongside
 *   the main one. `MediaItem` has no audio analogue of `SubtitleConfiguration`, so unlike
 *   [subtitles] these cannot ride along on the item: `ExoPlayerHandle.prepare` turns each into its
 *   own `MediaSource` and builds a `MergingMediaSource`. **Element `i` becomes merge child `i + 1`**
 *   — child 0 is always the main source — and that positional contract is the whole of the mapping
 *   `TrackSelectionController` navigates back by (DECISIONS.md 2026-07-31, "Offline multi-track
 *   Phase 2"). Only a transcoded download has any.
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
 * One audio track ExoPlayer loads as its own source and plays merged with the main one.
 *
 * It carries no id, unlike [SubtitleSpec]: the merge does not let us name a child's tracks, so the
 * bridge back to Jellyfin is the child's *position* rather than a string
 * (`TrackSelectionController.selectAudio`). Nor does it carry a label or a language — the picker
 * draws `PlaybackTrack`s built from the cached `BaseItemDto`, never ExoPlayer's own metadata.
 *
 * @property streamIndex the absolute Jellyfin `MediaStream.index` this file holds, kept so the
 *   merge order can be checked against the track list it was built from.
 */
internal data class AudioSidecarSpec(
    val streamIndex: Int,
    val uri: String,
)

/**
 * One side-loaded subtitle track.
 *
 * [id] carries the [EXTERNAL_SUBTITLE_ID_PREFIX] convention so that `TrackSelectionController`
 * can map an ExoPlayer text track back onto the Jellyfin stream index it came from.
 */
internal data class SubtitleSpec(
    val id: String,
    val uri: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

/** Prefix of the ExoPlayer track id given to a side-loaded Jellyfin subtitle stream. */
internal const val EXTERNAL_SUBTITLE_ID_PREFIX: String = "external:"

/** The ExoPlayer track id for the Jellyfin subtitle stream at [index]. */
internal fun externalSubtitleTrackId(index: Int): String = "$EXTERNAL_SUBTITLE_ID_PREFIX$index"

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
 *
 * Since phase 2 there can be **two** such prefixes. A downloaded item with both audio sidecars and
 * subtitles is merged twice: `ExoPlayerHandle` builds the outer merge over the audio files, and
 * `DefaultMediaSourceFactory` has already wrapped the main item in its own merge for the subtitles.
 * The same `external:2` then arrives as `0:1:external:2`, so the strip is a loop rather than one
 * step.
 */
internal fun jellyfinIndexOfTrackId(trackId: String?): Int? {
    val id = trackId?.withoutMergePrefixes() ?: return null
    if (!id.startsWith(EXTERNAL_SUBTITLE_ID_PREFIX)) return null
    return id.removePrefix(EXTERNAL_SUBTITLE_ID_PREFIX).toIntOrNull()
}

/**
 * The id as its own source published it, with every `MergingMediaPeriod` child prefix removed.
 *
 * Only leading runs of digits followed by `:` are stripped, which is exactly the shape a merge adds
 * and cannot be confused with our own prefix, which is not numeric. Stripping them all is safe for
 * a container track whose own id looks like one — Matroska names its tracks `1`, `2`, … so `0:0:2`
 * reduces to `2`, which is not an `external:` id and is still read as "none of ours".
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
