package dev.jellyfinnative.player.model

import dev.jellyfinnative.player.PlayMethod
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import java.util.UUID

/**
 * Everything the player needs to play one media source, independent of where it came from.
 *
 * The sealed type is the seam the plan relies on to make the player UI identical online and
 * offline (docs/PLAN.md, "Playback pipeline" → Offline): M5 ships [RemotePlaybackMediaSource] and
 * M8 adds a local variant built from `DownloadFileEntity` URIs. Nothing above this interface — the
 * ViewModel, the controls, the track pickers — should have to know which one it holds.
 */
sealed interface PlaybackMediaSource {
    /** The Jellyfin item being played. */
    val itemId: UUID

    /** Which of the item's media sources this is; a Jellyfin item can have several files. */
    val mediaSourceId: String

    /** How the bytes reach us. Drives URL construction and what a track switch costs. */
    val playMethod: PlayMethod

    /** Total runtime in Jellyfin ticks, or `0` when the server does not know it. */
    val runTimeTicks: Long

    /** Where playback should begin, in Jellyfin ticks. */
    val startPositionTicks: Long

    /** Selectable audio tracks, in Jellyfin stream order. */
    val audioTracks: List<PlaybackTrack>

    /** Selectable subtitle tracks, in Jellyfin stream order. */
    val subtitleTracks: List<PlaybackTrack>

    /** Subtitles delivered as separate files rather than inside the container. */
    val externalSubtitles: List<ExternalSubtitle>

    /** Jellyfin index of the active audio stream, or `null` when the server picked none. */
    val selectedAudioIndex: Int?

    /** Jellyfin index of the active subtitle stream; `null` means subtitles are off. */
    val selectedSubtitleIndex: Int?

    /**
     * The same source with a different audio track marked active.
     *
     * On the interface rather than left to each variant's `copy()` so that `PlayerViewModel` can
     * record a track switch without knowing which variant it is holding — the one thing that would
     * otherwise force an online/offline branch into the state holder.
     */
    fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource

    /** The same source with a different subtitle track marked active; `null` turns them off. */
    fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource
}

/**
 * A media source served by the Jellyfin server, resolved through `/Items/{id}/PlaybackInfo`.
 *
 * @param playSessionId the server's handle for this playback session. Every progress report and
 *   the `stopEncodingProcess` call that kills a stray ffmpeg process are keyed on it, so it must
 *   survive for as long as playback does.
 */
data class RemotePlaybackMediaSource(
    override val itemId: UUID,
    override val mediaSourceId: String,
    val playSessionId: String,
    override val playMethod: PlayMethod,
    /** Container of the source file (`mkv`, `mp4`, …); the direct-stream URL needs it. */
    val container: String?,
    /** How the server holds the file. `FILE` is the normal case; `HTTP` is a remote/live source. */
    val protocol: MediaProtocol,
    /** Direct URL the server supplied — only meaningful for an `HTTP`-protocol source. */
    val path: String?,
    /** Server-relative transcoding URL, present exactly when the server decided to transcode. */
    val transcodingUrl: String?,
    /** Transport of [transcodingUrl]; we only support HLS. */
    val transcodingSubProtocol: MediaStreamProtocol?,
    val liveStreamId: String?,
    /** Bitrate cap that produced this source, echoed back so a retry can lower it. */
    val maxStreamingBitrate: Int?,
    override val runTimeTicks: Long,
    override val startPositionTicks: Long,
    override val audioTracks: List<PlaybackTrack> = emptyList(),
    override val subtitleTracks: List<PlaybackTrack> = emptyList(),
    override val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    override val selectedAudioIndex: Int? = null,
    override val selectedSubtitleIndex: Int? = null,
) : PlaybackMediaSource {
    override fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource = copy(selectedAudioIndex = jellyfinIndex)

    override fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource =
        copy(selectedSubtitleIndex = jellyfinIndex)
}

/**
 * A media source played straight off this device's storage — the M8 half of the sealed type
 * (docs/PLAN.md, "Playback pipeline" → Offline).
 *
 * Everything it carries is derived from what the download pipeline already stored: the cached
 * `BaseItemDto`'s media source supplies the tracks and the runtime, and `DownloadFileEntity` rows
 * supply the URIs. Nothing here can reach the network, which is the point — it is
 * [PlayMethod.DIRECT_PLAY] by construction, there is no play session, and no progress report is
 * keyed on anything the server issued.
 *
 * @property mediaUri `file://` URI of the downloaded video file.
 * @property trickplay downloaded scrubbing thumbnails, when the server generated any. The scrubber
 *   that draws them arrives with M9; M8 only carries the data (DECISIONS.md 2026-07-29).
 */
data class LocalPlaybackMediaSource(
    override val itemId: UUID,
    override val mediaSourceId: String,
    val mediaUri: String,
    override val runTimeTicks: Long,
    override val startPositionTicks: Long,
    override val audioTracks: List<PlaybackTrack> = emptyList(),
    override val subtitleTracks: List<PlaybackTrack> = emptyList(),
    override val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    override val selectedAudioIndex: Int? = null,
    override val selectedSubtitleIndex: Int? = null,
    val trickplay: LocalTrickplay? = null,
) : PlaybackMediaSource {
    /** A file on local storage is always direct-played; there is nothing to remux or transcode. */
    override val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY

    override fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource = copy(selectedAudioIndex = jellyfinIndex)

    override fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource =
        copy(selectedSubtitleIndex = jellyfinIndex)
}

/**
 * Downloaded trickplay tile sheets and the geometry needed to index into them.
 *
 * A mirror of `:data:downloads`' `DownloadedTrickplay` rather than a re-export: the player's model
 * package is the vocabulary the player UI reads, and it does not otherwise depend on the download
 * pipeline's types.
 *
 * @property tileWidth thumbnails per row in one sheet; @property tileHeight rows per sheet.
 * @property intervalMs milliseconds of video between two consecutive thumbnails.
 */
data class LocalTrickplay(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
) {
    /**
     * The sheet, and the position inside it, holding the thumbnail for [positionMs].
     *
     * `null` when the geometry is unusable, or when the thumbnail would sit on a sheet that is not
     * on disk — which is what a position past the last generated thumbnail resolves to.
     */
    fun tileFor(positionMs: Long): TrickplayThumbnail? {
        val perTile = tileWidth * tileHeight
        if (intervalMs <= 0 || perTile <= 0) return null

        val thumbnail = (positionMs / intervalMs).toInt().coerceIn(0, (thumbnailCount - 1).coerceAtLeast(0))
        val uri = tileUris.getOrNull(thumbnail / perTile) ?: return null
        val withinTile = thumbnail % perTile
        return TrickplayThumbnail(
            uri = uri,
            column = withinTile % tileWidth,
            row = withinTile / tileWidth,
        )
    }
}

/** Where one trickplay thumbnail sits: which sheet, and which cell of it. */
data class TrickplayThumbnail(
    val uri: String,
    val column: Int,
    val row: Int,
)

/**
 * One selectable audio or subtitle track.
 *
 * [index] is the **absolute** Jellyfin `MediaStream.index`, not a position inside the filtered
 * audio or subtitle list — the server's stream-index parameters and ExoPlayer's track ids are both
 * matched against it, and confusing the two silently plays the wrong language.
 */
data class PlaybackTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean = false,
    val isExternal: Boolean = false,
)

/**
 * A subtitle ExoPlayer loads as its own side-loaded source.
 *
 * Both genuinely external subtitle files and subtitles the server extracts while transcoding
 * arrive this way (`SubtitleDeliveryMethod.EXTERNAL`).
 */
data class ExternalSubtitle(
    val index: Int,
    val url: String,
    val mimeType: String,
    val label: String,
    val language: String,
)
