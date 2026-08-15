package dev.jellyboost.player.model

import dev.jellyboost.player.PlayMethod
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
internal data class RemotePlaybackMediaSource(
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
    /**
     * `true` when [maxStreamingBitrate] was *measured* rather than chosen from the picker.
     *
     * The picker's chip is derived from this flag rather than by reverse-mapping the cap: a measured
     * 8 Mbps is indistinguishable from a hand-picked "Medium" by its number alone, which would both
     * mislabel Auto and swallow a genuine Medium tap (DECISIONS.md, 2026-08-15).
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
     * Prints no URL (audit SEC-12, same shape as `StoredSession.toString()`).
     *
     * [transcodingUrl] is built by the server with an `ApiKey` query parameter — the live access
     * token — so the generated data-class `toString()` prints a signed-in credential the moment an
     * instance reaches a log line or a wrapped exception message. Nothing does that today; the
     * point is that one `Timber.d("… %s", source)` is all it would take, and Media3's own error
     * logging is outside the Timber story entirely.
     *
     * The rule is *no URL*, not *not that one*: [path] is whatever the server put in `Path` (a URL
     * for an `HTTP`-protocol source, a path into somebody's library otherwise) and every
     * [ExternalSubtitle.url] is a server-issued delivery URL. Each is replaced by the one thing a
     * log actually wants from it — whether there is one, and how many. Everything else prints
     * exactly as the generated implementation would.
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
 * @property externalAudio audio tracks the download stored as their own files, which ExoPlayer has
 *   to be handed as extra sources rather than reading them out of the container. **The list order is
 *   a contract**: it is ascending Jellyfin stream index, and it is the order the merge children are
 *   built in, which is the only thing that ties an ExoPlayer audio group back to the Jellyfin stream
 *   behind it (DECISIONS.md 2026-07-31, "Offline multi-track Phase 2"). Empty for everything but a
 *   transcoded download — an original holds every track in the file, and a streamed source has no
 *   analogue at all, which is why this lives here rather than on [PlaybackMediaSource].
 * @property allAudioTracks / @property allSubtitleTracks every track of the **source**, as the
 *   cached blob describes it — a superset of [audioTracks] / [subtitleTracks], which are only what
 *   the file and its sidecars can actually play. The two lists are what makes the picker
 *   connectivity-aware: online it offers the source's full set and reaches anything extra by
 *   streaming it (`PlaybackResolveRequest.forceRemote`), offline it offers the playable subset and
 *   nothing else. They are deliberately *not* on [PlaybackMediaSource]: for a remote source the two
 *   sets are the same list, and every other collaborator — `TrackSelectionController` above all —
 *   must keep reading the playable one.
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
    /** A file on local storage is always direct-played; there is nothing to remux or transcode. */
    override val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY

    override fun withSelectedAudio(jellyfinIndex: Int?): PlaybackMediaSource = copy(selectedAudioIndex = jellyfinIndex)

    override fun withSelectedSubtitle(jellyfinIndex: Int?): PlaybackMediaSource =
        copy(selectedSubtitleIndex = jellyfinIndex)

    /** `true` when the bytes on disk can supply this audio track with no server in the loop. */
    fun playsAudioLocally(jellyfinIndex: Int): Boolean = audioTracks.any { it.index == jellyfinIndex }

    /** `true` when the file or one of its sidecars can supply this subtitle; `null` is "off". */
    fun playsSubtitleLocally(jellyfinIndex: Int?): Boolean =
        jellyfinIndex == null || subtitleTracks.any { it.index == jellyfinIndex }
}

/**
 * The audio tracks the picker should offer, given what the app can currently reach.
 *
 * Online, a downloaded item offers every track of the source: one the file does not hold is
 * satisfied by streaming it instead (`PlaybackResolveRequest.forceRemote`). Offline it offers only
 * what the file and its sidecars can play, because a picker entry that cannot do anything is worse
 * than one fewer language. A streamed source has one list either way.
 */
internal fun PlaybackMediaSource.audioTracksFor(online: Boolean): List<PlaybackTrack> =
    (this as? LocalPlaybackMediaSource)?.takeIf { online }?.allAudioTracks ?: audioTracks

/** The subtitle tracks the picker should offer; see [audioTracksFor]. */
internal fun PlaybackMediaSource.subtitleTracksFor(online: Boolean): List<PlaybackTrack> =
    (this as? LocalPlaybackMediaSource)?.takeIf { online }?.allSubtitleTracks ?: subtitleTracks

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
internal data class LocalTrickplay(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Int,
    val tileUris: List<String>,
) {
    /**
     * The same sheets in the vocabulary the scrubber draws from.
     *
     * The geometry is identical — only the names differ, because "tileWidth" means *thumbnails per
     * row* on the server and reads like a pixel width everywhere else. M9 renders
     * [TrickplayTiles], and a downloaded item reaches it through here.
     */
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

    /**
     * The sheet, and the position inside it, holding the thumbnail for [positionMs].
     *
     * `null` when the geometry is unusable, or when the thumbnail would sit on a sheet that is not
     * on disk — which is what a position past the last generated thumbnail resolves to.
     *
     * Delegates to [TrickplayTiles] since M9: the online scrubber needs the identical arithmetic
     * over sheets that live on the server, and two copies of it would be two chances to be wrong.
     */
    fun tileFor(positionMs: Long): TrickplayThumbnail? = toTiles().tileFor(positionMs)
}

/** Where one trickplay thumbnail sits: which sheet, and which cell of it. */
internal data class TrickplayThumbnail(
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

/**
 * One audio track ExoPlayer has to load as its own source, merged alongside the media file.
 *
 * Only a downloaded item has any: a transcoded download bakes exactly one audio track into the
 * video file and stores every other language as its own `.m4a` next to it
 * (docs/notes/offline-multitrack-design.md, phase 2). There is no `MediaItem` analogue of
 * `SubtitleConfiguration` for audio, so these are not carried on the spec's subtitle path — they
 * become merge children in `ExoPlayerHandle.prepare`, **in list order**.
 *
 * @property index the absolute Jellyfin `MediaStream.index` the file was fetched for.
 * @property uri `file://` URI of the audio-only sidecar.
 */
internal data class ExternalAudio(
    val index: Int,
    val uri: String,
)
