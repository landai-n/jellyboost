package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.UNDEFINED_LANGUAGE
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * An item that is a folder, not a video. `/Items/{id}/Download` answers `400` for one of these, so
 * the queue turns this into copy that says so instead of quoting the status code.
 */
internal class NotDownloadableException(
    itemId: UUID,
) : IllegalStateException("$itemId is a folder, not a downloadable file")

internal data class PlannedFile(
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
 * Turns a fully-fetched `BaseItemDto` into the ordered list of files to download.
 *
 * **Order is the contract**: the primary image (so the queue row and the notification have artwork
 * within a second), the media file, the backdrop and series poster, text subtitle sidecars, extra
 * audio language sidecars, then trickplay tiles. Only the media file is essential — everything after
 * it degrades the offline experience without making the item unplayable, which is why it comes after
 * and is attempted independently.
 *
 * A music track takes the much shorter [audioPlan] route.
 *
 * @param downloadAllowed the user's `enableContentDownloading` policy. `false` swaps the dedicated
 *   download endpoint for the static video stream — same bytes, a route the server does not gate.
 * @param quality anything but [DownloadQuality.ORIGINAL] replaces the media entry with a server-side
 *   transcode, which drops every embedded subtitle — hence a sidecar per extractable text subtitle.
 */
@Singleton
internal class DownloadFilePlanner
    @Inject
    constructor(
        private val urls: DownloadUrlFactory,
    ) {
        /**
         * @throws NotDownloadableException when [item] is a folder rather than a video. Callers expand
         *   containers first ([isFolderItem]); this makes one that forgot fail *before* a URL exists,
         *   rather than as an unexplained `400` from the server halfway down the queue.
         * @param audioStreamIndex the one audio track a transcode should bake in, or `null` for no pin
         *   (an `ORIGINAL` download, or an item with no audio streams). Every caller holding a row
         *   passes `bakedAudioStreamIndex` instead: the DTO's default audio stream is the server's
         *   *current* answer and can move between the tap and the drain, while the column is what the
         *   download actually asked for.
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
            if (item.type == BaseItemKind.AUDIO) return audioPlan(item, directoryName, downloadAllowed)

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
         * A music track's whole plan: the album's artwork, then the original file.
         *
         * No transcode, ever — audio download transcoding is deferred, and `DownloadEnqueuer` stamps
         * [DownloadQuality.ORIGINAL] on every audio row so nothing downstream (the size projector, the
         * no-resume rule, the *Transcoded* marker) can misread one. No subtitle or audio sidecars
         * either: that machinery exists to rescue tracks a transcode dropped.
         *
         * [BaseItemDto.albumPrimaryImageTag] is the album's own image, carried on every track, and it
         * is planned **per track** because the item directory is the unit of the delete cascade and of
         * the storage accounting — one shared file would vanish when its host track was deleted.
         */
        private fun audioPlan(
            item: BaseItemDto,
            directoryName: String,
            downloadAllowed: Boolean,
        ): List<PlannedFile> =
            buildList {
                (albumImage(item) ?: primaryImage(item))?.let(::add)
                add(
                    PlannedFile(
                        type = DownloadFileType.MEDIA,
                        fileName = DownloadPaths.mediaFileName(item, directoryName),
                        url =
                            if (downloadAllowed) {
                                urls.mediaUrl(item.id)
                            } else {
                                urls.staticAudioUrl(item.id, item.mediaSources?.firstOrNull()?.id)
                            },
                    ),
                )
            }

        /** The album cover of a track, addressed on the **album**, or `null` when it has none. */
        private fun albumImage(item: BaseItemDto): PlannedFile? {
            val albumId = item.albumId ?: return null
            val tag = item.albumPrimaryImageTag ?: return null
            return PlannedFile(
                type = DownloadFileType.IMAGE_PRIMARY,
                fileName = "primary.webp",
                url = urls.imageUrl(albumId, ImageType.PRIMARY, tag, PRIMARY_IMAGE_WIDTH),
            )
        }

        /**
         * The media entry: the dedicated download endpoint, the static video stream (the same bytes
         * for a user whose `enableContentDownloading` policy is off), or — when a smaller file was
         * asked for — a transcode, which is never a fallback for either of the others.
         *
         * A transcode also renames the file: the bytes are [DownloadQuality.CONTAINER] whatever the
         * source container was, and the quality goes in the name so a re-download at another step
         * cannot land on the old file.
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

        /** The parent series' poster, so a downloaded episode can render its show offline. */
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
         * Text subtitle tracks fetched as sidecar files, one file each. Three filters:
         *
         * - **[MediaStream.supportsExternalStream]** — the server's own statement that it will extract
         *   the embedded track on demand. Not [MediaStream.isExternal], which asks whether it is
         *   already a file next to the video and answers `false` for every embedded SRT;
         *   `deliveryMethod` is populated only by playback-info negotiation, which a download never runs.
         * - **[TEXT_SUBTITLE_CODECS]** — bitmap formats (PGS, VobSub) cannot be side-loaded at all, so
         *   a sidecar for one would be a track that exists and never renders.
         * - **the row's [quality]**, for embedded streams only — an `ORIGINAL` download *is* the source
         *   file, so a sidecar would duplicate bytes and give the picker two routes to one track, the
         *   side-loaded copy silently winning. A transcode drops them, so there it is the only copy.
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
         * sidecar. Guarded on [DownloadQuality.isTranscoded] — an `ORIGINAL` already holds every track
         * in the one file — and on [audioStreamIndex] being non-null, which is the plan's own record of
         * "no audio streams at all".
         *
         * See [DownloadUrlFactory.audioStreamUrl] for why a sidecar is fetched through `/Videos` with a
         * junk video track rather than through the audio-only endpoint its name would suggest.
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
         * `MediaStream.language` is the raw container track tag from ffprobe — controlled by whoever
         * supplied the media file, not by the server — and `FileDownloader` runs `mkdirs()` on the
         * parent, so a tag containing `../` or `/` would write attacker-influenced bytes outside the
         * item directory, where neither the delete cascade nor the orphan sweep collects them.
         */
        private fun sidecarLanguage(raw: String?): String =
            raw
                ?.filter { it.isLetterOrDigit() || it == '-' }
                ?.take(MAX_LANGUAGE_LENGTH)
                ?.takeIf { it.isNotBlank() }
                ?: UNDEFINED_LANGUAGE

        /**
         * Every trickplay tile sheet of the *largest* resolution the server generated: a server can
         * hold several widths and the scrubber only ever draws one. The tile count is derived rather
         * than served — each sheet holds `tileWidth × tileHeight` thumbnails.
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
