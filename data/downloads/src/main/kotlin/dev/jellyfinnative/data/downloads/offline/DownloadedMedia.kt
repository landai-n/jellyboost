package dev.jellyfinnative.data.downloads.offline

import dev.jellyfinnative.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.net.URI
import java.util.UUID

/**
 * Everything on this device that is needed to play one downloaded item without a network
 * (docs/PLAN.md, "Playback pipeline" → Offline).
 *
 * It is the hand-off between the download pipeline, which knows what is on disk, and `:player`'s
 * `LocalPlaybackResolver`, which turns it into a `PlaybackMediaSource`. The split is deliberate:
 * the DAOs and the storage layout stay inside `:data:downloads`, and the player never learns that
 * Room exists.
 *
 * [mediaSource] is the SDK's own description of the file — its streams are what the audio and
 * subtitle pickers are built from, and it is the *same* type the online path negotiates, which is
 * what makes the two produce identical track lists.
 *
 * [quality] is the one thing that stops [mediaSource] from being taken at face value. It records
 * what was *asked of the server*, and for anything but [DownloadQuality.ORIGINAL] the file on disk
 * is a re-encode that no longer matches the streams the cached blob describes — exactly one audio
 * track and no embedded subtitles at all, because the endpoint takes one `audioStreamIndex` and
 * drops everything else. `LocalPlaybackResolver` needs it to keep the offline pickers from offering
 * tracks that are not in the file. [bakedAudioStreamIndex] says *which* audio track that is; the
 * subtitles that survive come back as [subtitles], one sidecar file each.
 *
 * @property mediaUri `file://` URI of the video file itself.
 * @property runTimeTicks runtime in Jellyfin ticks, `0` when neither the media source nor the item
 *   declares one (the player then takes the container's duration once ExoPlayer reports it).
 * @property quality the download-quality step the row was enqueued at.
 * @property bakedAudioStreamIndex the absolute `MediaStream.index` of the single audio track a
 *   transcode encoded (schema v8). `null` for an `ORIGINAL` download, which holds them all, and for
 *   a transcoded row written before v8 — those fall back to assuming the source's
 *   `defaultAudioStreamIndex`, which is what the server would have picked with no index named.
 * @property audio the extra audio tracks of a transcoded download, sorted ascending by
 *   [DownloadedAudio.streamIndex] — **this order is the contract**: the player builds its
 *   `MergingMediaSource` children in exactly this order and maps ExoPlayer track groups back to
 *   Jellyfin stream indices by position (docs/notes/offline-multitrack-design.md, phase 2). Empty
 *   for an `ORIGINAL` download, where every track is already in the file.
 */
data class DownloadedMedia(
    val itemId: UUID,
    val mediaSourceId: String,
    val mediaSource: MediaSourceInfo?,
    val mediaUri: String,
    val runTimeTicks: Long,
    val quality: DownloadQuality = DownloadQuality.ORIGINAL,
    val bakedAudioStreamIndex: Int? = null,
    val subtitles: List<DownloadedSubtitle> = emptyList(),
    val audio: List<DownloadedAudio> = emptyList(),
    val trickplay: DownloadedTrickplay? = null,
) {
    /** `true` when the bytes on disk are a server re-encode rather than the source file. */
    val isTranscoded: Boolean get() = quality.isTranscoded
}

/**
 * One downloaded external subtitle file.
 *
 * @property streamIndex the **absolute** Jellyfin `MediaStream.index` the file was fetched for —
 *   the only thing that ties the sidecar back to the track the picker offers.
 */
data class DownloadedSubtitle(
    val streamIndex: Int,
    val uri: String,
)

/**
 * One extra audio language of a transcoded download.
 *
 * A local `.m4a` produced by the post-fetch strip, not the server's own file — and unlike the media
 * mkv, it needs no seek-index repair: the transmux that made it wrote a complete `moov`, so it is
 * natively seekable from the moment it lands on disk.
 *
 * @property streamIndex the **absolute** Jellyfin `MediaStream.index` the track was fetched for —
 *   the only thing that ties the sidecar back to the track the picker offers.
 */
data class DownloadedAudio(
    val streamIndex: Int,
    val uri: String,
)

/**
 * The downloaded trickplay tile sheets of one item, with the geometry needed to address them.
 *
 * The scrubber that draws these is M9 (docs/PLAN.md, "M9 Polish" → trickplay scrubber); M8 only
 * makes the data reachable from a playing local source, so that the scrubber is a UI change rather
 * than a data change (DECISIONS.md 2026-07-29).
 *
 * @property width pixel width of a single thumbnail — also the resolution the tiles were requested
 *   at, and part of their URL.
 * @property tileWidth thumbnails per row inside one sheet.
 * @property tileHeight rows of thumbnails inside one sheet.
 * @property thumbnailCount total thumbnails across every sheet.
 * @property intervalMs milliseconds of video between two thumbnails.
 * @property tileUris `file://` URIs of the sheets, in tile order.
 */
data class DownloadedTrickplay(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
)

/**
 * An absolute filesystem path as a `file://` URI ExoPlayer can open.
 *
 * Built through [URI] rather than by string concatenation because downloaded files are named after
 * the media (`A Movie (2026)/A Movie #1.mkv`), and a bare `#` in a concatenated URI would be parsed
 * as a fragment and truncate the path. The five-argument constructor with an empty authority is what
 * produces the conventional `file:///…` form with every illegal character percent-encoded;
 * `File.toURI()` would produce the authority-less `file:/…` spelling instead.
 */
internal fun localFileUri(path: String): String = URI("file", "", path, null, null).toString()
