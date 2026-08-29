package dev.jellyboost.data.downloads.offline

import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.net.URI
import java.util.UUID

/**
 * Everything on this device that is needed to play one downloaded item without a network — the
 * hand-off between the download pipeline and `:player`'s `LocalPlaybackResolver`, so the player never
 * learns that Room exists.
 *
 * [quality] is the one thing that stops [mediaSource] from being taken at face value: for anything but
 * [DownloadQuality.ORIGINAL] the file on disk is a re-encode holding exactly one audio track and no
 * embedded subtitles, because the endpoint takes one `audioStreamIndex` and drops everything else.
 *
 * @property runTimeTicks `0` when neither the media source nor the item declares one; the player then
 *   takes the container's duration once ExoPlayer reports it.
 * @property bakedAudioStreamIndex the absolute `MediaStream.index` of the single audio track a
 *   transcode encoded. `null` for an `ORIGINAL` download, which holds them all, and for a transcoded
 *   row written before the column existed — those fall back to the source's `defaultAudioStreamIndex`,
 *   which is what the server would have picked with no index named.
 * @property audio the extra audio tracks of a transcoded download, sorted ascending by
 *   [DownloadedAudio.streamIndex] — **this order is the contract**: the player builds its
 *   `MergingMediaSource` children in exactly this order and maps ExoPlayer track groups back to
 *   Jellyfin stream indices by position.
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
    val fonts: List<DownloadedFont> = emptyList(),
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
 * One extra audio language of a transcoded download: a local `.m4a` produced by the post-fetch strip.
 * Unlike the media mkv it needs no seek-index repair — the transmux wrote a complete `moov`.
 *
 * @property streamIndex the **absolute** Jellyfin `MediaStream.index` the track was fetched for — the
 *   only thing that ties the sidecar back to the track the picker offers.
 */
data class DownloadedAudio(
    val streamIndex: Int,
    val uri: String,
)

/**
 * One font attached to the source container, downloaded because this row is a transcode and the server's
 * re-encode dropped the attachments an ASS/SSA sidecar's styles name.
 *
 * @property name a label, not a lookup key: libass parses the blob with FreeType and matches styles on the
 *   face's own family names, so a sanitised filename here costs nothing.
 * @property path an absolute filesystem path rather than a `file://` URI — these bytes are read by the app
 *   and handed to libass, never opened by ExoPlayer.
 */
data class DownloadedFont(
    val name: String,
    val path: String,
)

/**
 * The downloaded trickplay tile sheets of one item, with the geometry needed to address them.
 *
 * @property width pixel width of a single thumbnail — also the resolution the tiles were requested at.
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
 * An absolute filesystem path as a `file://` URI ExoPlayer can open. Built through [URI] rather than
 * by string concatenation: downloaded files are named after the media (`A Movie (2026)/A Movie #1.mkv`)
 * and a bare `#` would be parsed as a fragment and truncate the path. The five-argument constructor
 * with an empty authority gives the conventional `file:///…` form; `File.toURI()` gives `file:/…`.
 */
internal fun localFileUri(path: String): String = URI("file", "", path, null, null).toString()
