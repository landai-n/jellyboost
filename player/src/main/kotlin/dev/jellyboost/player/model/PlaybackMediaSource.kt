package dev.jellyboost.player.model

import dev.jellyboost.player.PlayMethod
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import java.util.UUID

sealed interface PlaybackMediaSource {
    val itemId: UUID

    val mediaSourceId: String

    val playMethod: PlayMethod

    /** Jellyfin ticks; `0` when the server does not know the runtime. */
    val runTimeTicks: Long

    /** Jellyfin ticks. */
    val startPositionTicks: Long

    val audioTracks: List<PlaybackTrack>

    val subtitleTracks: List<PlaybackTrack>

    val externalSubtitles: List<ExternalSubtitle>

    /** Absolute Jellyfin `MediaStream.index`; `null` when the server picked none. */
    val selectedAudioIndex: Int?

    /** Absolute Jellyfin `MediaStream.index`; `null` means subtitles are off. */
    val selectedSubtitleIndex: Int?

    fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource

    /** `null` turns subtitles off. */
    fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource
}

/**
 * @param playSessionId keys every progress report and the `stopEncodingProcess` call that kills a
 *   stray ffmpeg process, so it must survive for as long as playback does.
 */
internal data class RemotePlaybackMediaSource(
    override val itemId: UUID,
    override val mediaSourceId: String,
    val playSessionId: String,
    override val playMethod: PlayMethod,
    val container: String?,
    val protocol: MediaProtocol,
    /** Only meaningful when [protocol] is `HTTP` (a remote/live source). */
    val path: String?,
    /** Present exactly when the server decided to transcode. */
    val transcodingUrl: String?,
    /** Transport of [transcodingUrl]; only HLS is supported. */
    val transcodingSubProtocol: MediaStreamProtocol?,
    val liveStreamId: String?,
    /** The cap that produced this source, echoed back so a retry can lower it. */
    val maxStreamingBitrate: Int?,
    /**
     * `true` when [maxStreamingBitrate] was *measured* rather than picked. The picker's chip reads
     * this flag: a measured 8 Mbps is indistinguishable from a hand-picked "Medium" by number alone.
     */
    val autoBitrate: Boolean = false,
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

    /**
     * The rule is *no URL at all*: [transcodingUrl] carries the access token as an `ApiKey` query
     * parameter, [path] can be a library path, and [ExternalSubtitle.url] is server-issued. Keep any
     * new URL-bearing field out of this string.
     */
    override fun toString(): String =
        "RemotePlaybackMediaSource(" +
            "itemId=$itemId, mediaSourceId=$mediaSourceId, playSessionId=$playSessionId, " +
            "playMethod=$playMethod, container=$container, protocol=$protocol, " +
            "path=${redacted(path)}, transcodingUrl=${redacted(transcodingUrl)}, " +
            "transcodingSubProtocol=$transcodingSubProtocol, liveStreamId=$liveStreamId, " +
            "maxStreamingBitrate=$maxStreamingBitrate, autoBitrate=$autoBitrate, " +
            "runTimeTicks=$runTimeTicks, " +
            "startPositionTicks=$startPositionTicks, audioTracks=$audioTracks, " +
            "subtitleTracks=$subtitleTracks, externalSubtitles=${externalSubtitles.size}, " +
            "selectedAudioIndex=$selectedAudioIndex, selectedSubtitleIndex=$selectedSubtitleIndex)"

    private companion object {
        /** `null` stays readable — its absence is a fact about the source, not a secret. */
        fun redacted(value: String?): String = if (value == null) "null" else "<redacted>"
    }
}

/**
 * @property externalAudio **the list order is a contract**: ascending Jellyfin stream index, and the
 *   order the merge children are built in — the only thing tying an ExoPlayer audio group back to
 *   its Jellyfin stream. Non-empty only for a transcoded download.
 * @property allAudioTracks / @property allSubtitleTracks every track of the *source*, a superset of
 *   [audioTracks] / [subtitleTracks], which are only what the file and its sidecars can play. Kept
 *   off [PlaybackMediaSource] deliberately: every other collaborator must keep reading the playable
 *   list.
 */
internal data class LocalPlaybackMediaSource(
    override val itemId: UUID,
    override val mediaSourceId: String,
    val mediaUri: String,
    override val runTimeTicks: Long,
    override val startPositionTicks: Long,
    override val audioTracks: List<PlaybackTrack> = emptyList(),
    override val subtitleTracks: List<PlaybackTrack> = emptyList(),
    override val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val externalAudio: List<ExternalAudio> = emptyList(),
    val allAudioTracks: List<PlaybackTrack> = audioTracks,
    val allSubtitleTracks: List<PlaybackTrack> = subtitleTracks,
    override val selectedAudioIndex: Int? = null,
    override val selectedSubtitleIndex: Int? = null,
    val trickplay: LocalTrickplay? = null,
) : PlaybackMediaSource {
    override val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY

    override fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource = copy(selectedAudioIndex = jellyfinIndex)

    override fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource =
        copy(selectedSubtitleIndex = jellyfinIndex)

    fun playsAudioLocally(jellyfinIndex: Int): Boolean = audioTracks.any { it.index == jellyfinIndex }

    /** `null` is "off", which is always playable locally. */
    fun playsSubtitleLocally(jellyfinIndex: Int?): Boolean =
        jellyfinIndex == null || subtitleTracks.any { it.index == jellyfinIndex }
}

/**
 * Online, a downloaded item offers every track of the source — one the file lacks is satisfied by
 * streaming it (`PlaybackResolveRequest.forceRemote`); offline, only the playable subset.
 */
internal fun PlaybackMediaSource.audioTracksFor(online: Boolean): List<PlaybackTrack> =
    (this as? LocalPlaybackMediaSource)?.takeIf { online }?.allAudioTracks ?: audioTracks

internal fun PlaybackMediaSource.subtitleTracksFor(online: Boolean): List<PlaybackTrack> =
    (this as? LocalPlaybackMediaSource)?.takeIf { online }?.allSubtitleTracks ?: subtitleTracks

/**
 * @property tileWidth thumbnails per row in one sheet (not pixels); @property tileHeight rows per sheet.
 * @property intervalMs milliseconds of video between two consecutive thumbnails.
 */
internal data class LocalTrickplay(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
) {
    /** Same geometry, renamed: the server's `tileWidth`/`tileHeight` are counts, not pixel sizes. */
    fun toTiles(): TrickplayTiles =
        TrickplayTiles(
            thumbnailWidth = width,
            thumbnailHeight = height,
            columns = tileWidth,
            rows = tileHeight,
            thumbnailCount = thumbnailCount,
            intervalMs = intervalMs,
            tileUris = tileUris,
        )

    /** `null` when the geometry is unusable or the sheet is not on disk (a position past the last one). */
    fun tileFor(positionMs: Long): TrickplayThumbnail? = toTiles().tileFor(positionMs)
}

internal data class TrickplayThumbnail(
    val uri: String,
    val column: Int,
    val row: Int,
)

/**
 * [index] is the **absolute** Jellyfin `MediaStream.index`, never a position in the filtered audio
 * or subtitle list — confusing the two silently plays the wrong language.
 */
data class PlaybackTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean = false,
    val isExternal: Boolean = false,
)

/** Also carries subtitles the server *extracts* while transcoding (`SubtitleDeliveryMethod.EXTERNAL`). */
data class ExternalSubtitle(
    val index: Int,
    val url: String,
    val mimeType: String,
    val label: String,
    val language: String,
)

/**
 * There is no `MediaItem` analogue of `SubtitleConfiguration` for audio: these become merge children
 * in `ExoPlayerHandle.prepare`, **in list order**.
 *
 * @property index the absolute Jellyfin `MediaStream.index` the file was fetched for.
 */
internal data class ExternalAudio(
    val index: Int,
    val uri: String,
)
