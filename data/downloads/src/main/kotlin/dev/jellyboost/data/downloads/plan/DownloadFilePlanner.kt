package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.UNDEFINED_LANGUAGE
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * An item that is a folder, not a video — a series, a season, a box set.
 *
 * `/Items/{id}/Download` answers `400` for one of these, which is the error the user saw when
 * tapping Download on a season used to enqueue the season itself (docs/POLISH.md, DECISIONS.md
 * 2026-07-29). The queue turns this into copy that says so, instead of quoting the status code.
 */
internal class NotDownloadableException(
    itemId: UUID,
) : IllegalStateException("$itemId is a folder, not a downloadable file")

/**
 * One file the pipeline intends to fetch, before anything has touched Room or the disk.
 *
 * @param essential mirrors [DownloadFileType.essential]; carried on the plan so the queue does not
 *   have to look it up while deciding whether a failure is fatal.
 */
data class PlannedFile(
    val type: DownloadFileType,
    val fileName: String,
    val url: String,
    val streamIndex: Int? = null,
    val tileIndex: Int? = null,
    val tileWidth: Int? = null,
) {
    /** `true` when the item is not playable offline without this file. */
    val essential: Boolean get() = type.essential
}

/**
 * Turns a fully-fetched `BaseItemDto` into the ordered list of files to download (docs/PLAN.md,
 * "Download pipeline" → File plan).
 *
 * **Order is the contract**, and it is the plan's:
 * 1. the primary image, so the queue row and the notification have artwork within a second;
 * 2. the media file, the item's whole point and usually 99.9 % of the bytes;
 * 3. the backdrop and the parent series' poster, which the offline detail page draws;
 * 4. text subtitle sidecars, one per stream — kilobytes, so they finish first;
 * 5. extra audio language sidecars, one per stream not already baked into the media file — every
 *    other language outranks scrub thumbnails, but not the subtitles a viewer needs from second one;
 * 6. trickplay tiles, for offline scrubbing (M9 renders them).
 *
 * Only step 2 is essential. Everything else failing degrades the offline experience without making
 * the item unplayable, which is why they come after it and are attempted independently.
 *
 * @param downloadAllowed the user's `enableContentDownloading` policy. `false` swaps the dedicated
 *   download endpoint for the static video stream — same bytes, a route the server does not gate on
 *   that policy.
 * @param quality the *download quality* stamped on the row when the user tapped Download (M9).
 *   Anything but [DownloadQuality.ORIGINAL] replaces the media entry with a server-side transcode —
 *   and, since the transcode drops every embedded subtitle, adds a sidecar for each embedded text
 *   subtitle the server can extract. Artwork and trickplay tiles are the same files either way.
 */
@Singleton
class DownloadFilePlanner
    @Inject
    constructor(
        private val urls: DownloadUrlFactory,
    ) {
        /**
         * Builds the plan.
         *
         * @return an ordered list; an item whose media source is missing still yields the media
         *   entry, because the download endpoint does not need one.
         * @throws NotDownloadableException when [item] is a folder rather than a video. Callers
         *   expand containers into their episodes before they ever get here ([isFolderItem]); this
         *   is the guard that makes a caller which forgot fail *before* a URL exists rather than as
         *   an unexplained `400` from the server halfway down the queue.
         *
         * @param audioStreamIndex the one audio track a transcode should bake in, or `null` for no
         *   pin at all (an `ORIGINAL` download, which keeps every track, or an item with no audio
         *   streams). Defaults to the rule [downloadAudioStreamIndex] states, which is the rule
         *   `DownloadEnqueuer` applies when it stamps `bakedAudioStreamIndex` on the row — so a
         *   caller with no row in hand still gets the enqueue-time answer. Every caller that *has*
         *   a row passes that column instead: the DTO's default audio stream is the server's
         *   current answer and can move between the tap and the drain, while the column is what
         *   the download actually asked for (DECISIONS.md, 2026-07-30).
         */
        @Suppress("LongParameterList")
        fun plan(
            item: BaseItemDto,
            directoryName: String,
            downloadAllowed: Boolean = true,
            quality: DownloadQuality = DownloadQuality.ORIGINAL,
            audioStreamIndex: Int? = item.downloadAudioStreamIndex,
        ): List<PlannedFile> {
            if (item.isFolderItem) throw NotDownloadableException(item.id)

            val mediaSource = item.mediaSources?.firstOrNull()
            val mediaSourceId = mediaSource?.id

            return buildList {
                primaryImage(item)?.let(::add)
                add(media(item, directoryName, mediaSourceId, downloadAllowed, quality, audioStreamIndex))
                backdropImage(item)?.let(::add)
                seriesImage(item)?.let(::add)
                if (mediaSourceId != null) {
                    val streams = mediaSource.mediaStreams.orEmpty()
                    addAll(subtitles(item, mediaSourceId, streams, quality))
                    addAll(audioSidecars(item, mediaSourceId, streams, quality, audioStreamIndex))
                }
                addAll(trickplayTiles(item))
            }
        }

        /**
         * The media entry.
         *
         * Three routes to the same slot, in order of preference: the dedicated download endpoint
         * (the original file), the static video stream (the same bytes for a user whose
         * `enableContentDownloading` policy is off), and — when the user asked for a smaller file —
         * a transcode, which is neither of the other two and is never a fallback for them.
         *
         * A transcode also renames the file: the bytes are [DownloadQuality.CONTAINER] whatever the
         * source container was, and a name carrying the source's extension is one ExoPlayer sniffs
         * its way out of but a user plugging the tablet into a computer does not. The quality goes
         * in the name too, so a re-download at another step cannot land on the old file.
         */
        @Suppress("LongParameterList")
        private fun media(
            item: BaseItemDto,
            directoryName: String,
            mediaSourceId: String?,
            downloadAllowed: Boolean,
            quality: DownloadQuality,
            audioStreamIndex: Int?,
        ): PlannedFile =
            PlannedFile(
                type = DownloadFileType.MEDIA,
                fileName = DownloadPaths.mediaFileName(item, directoryName, quality),
                url =
                    when {
                        quality.isTranscoded ->
                            urls.transcodedVideoUrl(item.id, mediaSourceId, quality, audioStreamIndex)
                        downloadAllowed -> urls.mediaUrl(item.id)
                        else -> urls.videoStreamUrl(item.id, mediaSourceId)
                    },
            )

        private fun primaryImage(item: BaseItemDto): PlannedFile? =
            item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                PlannedFile(
                    type = DownloadFileType.IMAGE_PRIMARY,
                    fileName = "primary.webp",
                    url = urls.imageUrl(item.id, ImageType.PRIMARY, tag, PRIMARY_IMAGE_WIDTH),
                )
            }

        private fun backdropImage(item: BaseItemDto): PlannedFile? =
            item.backdropImageTags?.firstOrNull()?.let { tag ->
                PlannedFile(
                    type = DownloadFileType.IMAGE_BACKDROP,
                    fileName = "backdrop.webp",
                    url = urls.imageUrl(item.id, ImageType.BACKDROP, tag, BACKDROP_IMAGE_WIDTH),
                )
            }

        /**
         * The parent series' poster, so a downloaded episode can render its show offline without
         * the series itself ever being downloaded.
         */
        private fun seriesImage(item: BaseItemDto): PlannedFile? {
            val seriesId = item.seriesId ?: return null
            val tag = item.seriesPrimaryImageTag ?: return null
            return PlannedFile(
                type = DownloadFileType.IMAGE_SERIES_PRIMARY,
                fileName = "series-primary.webp",
                url = urls.imageUrl(seriesId, ImageType.PRIMARY, tag, SERIES_IMAGE_WIDTH),
            )
        }

        /**
         * Text subtitle tracks fetched as sidecar files, one file each.
         *
         * Three filters, and each answers a different question:
         *
         * - **[MediaStream.supportsExternalStream]** — *can the server hand this stream over on its
         *   own?* `/Videos/{id}/{msId}/Subtitles/{index}/Stream.{format}` extracts an embedded
         *   track with ffmpeg on demand, and this flag is the server's own statement that it will.
         *   It replaces the old [MediaStream.isExternal] test, which asked a different question
         *   (*is this already a file next to the video?*) and answered `false` for every embedded
         *   SRT — the reason a transcoded download used to lose subtitles it could perfectly well
         *   have kept (docs/notes/offline-multitrack-design.md, phase 0). `deliveryMethod` is used
         *   for neither: it is only populated by playback-info negotiation, and a download works
         *   from a plain item request.
         * - **[TEXT_SUBTITLE_CODECS]** — *can ExoPlayer play it from a standalone file?* Bitmap
         *   formats (PGS, VobSub) cannot be side-loaded at all, so a sidecar for one would be a
         *   track that exists and never renders. They survive only in an `ORIGINAL` download, which
         *   is the honest trade the quality picker makes.
         * - **the row's [quality]**, for embedded streams only — *would this sidecar be a second
         *   copy of bytes we already have?* An `ORIGINAL` download **is** the source file, embedded
         *   subtitles and all; fetching them again would spend bandwidth on a duplicate and give the
         *   picker two routes to one track (the side-loaded copy silently winning, since
         *   `TrackSelectionController` matches `external:<index>` ids before anything else). A
         *   transcode drops them on the server, so there the sidecar is the only copy there will
         *   ever be. Genuinely external streams are their own file at every quality and are
         *   unaffected by this.
         */
        private fun subtitles(
            item: BaseItemDto,
            mediaSourceId: String,
            streams: List<MediaStream>,
            quality: DownloadQuality,
        ): List<PlannedFile> =
            streams
                .filter { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        stream.supportsExternalStream &&
                        stream.codec?.lowercase() in TEXT_SUBTITLE_CODECS &&
                        (stream.isExternal || quality.isTranscoded)
                }.map { stream ->
                    val format = SUBTITLE_FORMATS[stream.codec?.lowercase()] ?: DEFAULT_SUBTITLE_FORMAT
                    PlannedFile(
                        type = DownloadFileType.SUBTITLE,
                        fileName = "subtitle.${stream.index}.${sidecarLanguage(stream.language)}.$format",
                        url = urls.subtitleUrl(item.id, mediaSourceId, stream.index, format),
                        streamIndex = stream.index,
                    )
                }

        /**
         * Every audio language a transcode did *not* bake into the media file, fetched as its own
         * sidecar (docs/notes/offline-multitrack-design.md, phase 2).
         *
         * Two guards, both narrower than [subtitles]'s: [DownloadQuality.isTranscoded], because an
         * `ORIGINAL` download already holds every track in the one file — a sidecar there would be a
         * duplicate, exactly as for an embedded subtitle — and [audioStreamIndex] not `null`, because
         * that is the plan's own record of "no audio streams at all" (no track exists to bake in,
         * so none exist to sidecar either). What survives the guards is every [MediaStreamType.AUDIO]
         * stream of the first media source except the one [audioStreamIndex] names — that one is
         * already in the media file the sibling `media()` entry is fetching.
         *
         * The URL, not the bytes, is where the real work happens: see [DownloadUrlFactory.audioStreamUrl]
         * for why an audio sidecar is fetched through `/Videos` with a junk video track rather than
         * through the audio-only endpoint its name would suggest.
         */
        private fun audioSidecars(
            item: BaseItemDto,
            mediaSourceId: String,
            streams: List<MediaStream>,
            quality: DownloadQuality,
            audioStreamIndex: Int?,
        ): List<PlannedFile> {
            if (!quality.isTranscoded || audioStreamIndex == null) return emptyList()

            return streams
                .filter { stream -> stream.type == MediaStreamType.AUDIO && stream.index != audioStreamIndex }
                .map { stream ->
                    val language = sidecarLanguage(stream.language)
                    PlannedFile(
                        type = DownloadFileType.AUDIO,
                        fileName = "audio.${stream.index}.$language.${DownloadQuality.AUDIO_SIDECAR_CONTAINER}",
                        url = urls.audioStreamUrl(item.id, mediaSourceId, stream.index),
                        streamIndex = stream.index,
                    )
                }
        }

        /**
         * A stream's language tag, made safe to interpolate into a file name.
         *
         * `MediaStream.language` is the raw container track tag from ffprobe — controlled by
         * whoever supplied the media file, not by the server. Interpolated verbatim it reached
         * `File(root/dir, fileName)` with `FileDownloader` running `mkdirs()` on the parent, so a
         * tag containing `../` or `/` wrote attacker-influenced bytes *outside* the item directory
         * — where neither the delete cascade nor the orphan sweep ever collects them (audit
         * DL-15). Restricted to the alphabet a real language tag uses (letters, digits, `-`),
         * bounded, and falling back to the server's own "undetermined" code when nothing survives.
         */
        private fun sidecarLanguage(raw: String?): String =
            raw
                ?.filter { it.isLetterOrDigit() || it == '-' }
                ?.take(MAX_LANGUAGE_LENGTH)
                ?.takeIf { it.isNotBlank() }
                ?: UNDEFINED_LANGUAGE

        /**
         * Every trickplay tile sheet of the *largest* resolution the server generated.
         *
         * A server can hold several widths; downloading all of them would multiply the tile count
         * for a scrubber that only ever draws one. The tile count is derived rather than served:
         * the sheets hold `tileWidth × tileHeight` thumbnails each, so a 900-thumbnail item at
         * 10×10 per sheet is 9 files.
         */
        private fun trickplayTiles(item: BaseItemDto): List<PlannedFile> {
            val bestWidth =
                item.trickplay
                    ?.values
                    ?.flatMap { byWidth -> byWidth.entries }
                    ?.maxByOrNull { it.value.width }
                    ?: return emptyList()

            val info = bestWidth.value
            val perTile = info.tileWidth * info.tileHeight
            if (perTile <= 0 || info.thumbnailCount <= 0) return emptyList()

            val tileCount = ceil(info.thumbnailCount.toDouble() / perTile).toInt()
            return (0 until tileCount).map { index ->
                PlannedFile(
                    type = DownloadFileType.TRICKPLAY_TILE,
                    fileName = "trickplay.${info.width}.$index.jpg",
                    url = urls.trickplayTileUrl(item.id, info.width, index),
                    tileIndex = index,
                    tileWidth = info.width,
                )
            }
        }

        companion object {
            /** Wide enough for a poster on a tablet without storing the source artwork. */
            const val PRIMARY_IMAGE_WIDTH = 480

            /** The offline detail header draws this full-bleed, so it gets more pixels. */
            const val BACKDROP_IMAGE_WIDTH = 1280

            /** Only ever drawn as a small show poster next to an episode. */
            const val SERIES_IMAGE_WIDTH = 300

            /** Codecs ExoPlayer can play from a standalone sidecar file. */
            val TEXT_SUBTITLE_CODECS = setOf("srt", "subrip", "ssa", "ass", "ttml", "vtt", "webvtt")

            /** Maps a stream codec onto the extension the server converts to. */
            val SUBTITLE_FORMATS =
                mapOf(
                    "subrip" to "srt",
                    "srt" to "srt",
                    "ass" to "ass",
                    "ssa" to "ssa",
                    "ttml" to "ttml",
                    "vtt" to "vtt",
                    "webvtt" to "vtt",
                )

            private const val DEFAULT_SUBTITLE_FORMAT = "srt"

            /** Longer than any real BCP-47 tag; short enough that a hostile one cannot ENAMETOOLONG. */
            private const val MAX_LANGUAGE_LENGTH = 20
        }
    }
