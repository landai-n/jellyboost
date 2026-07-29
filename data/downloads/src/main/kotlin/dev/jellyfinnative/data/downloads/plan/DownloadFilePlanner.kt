package dev.jellyfinnative.data.downloads.plan

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadQuality
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

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
 * 4. external text subtitles, one per stream;
 * 5. trickplay tiles, for offline scrubbing (M9 renders them).
 *
 * Only step 2 is essential. Everything else failing degrades the offline experience without making
 * the item unplayable, which is why they come after it and are attempted independently.
 *
 * @param downloadAllowed the user's `enableContentDownloading` policy. `false` swaps the dedicated
 *   download endpoint for the static video stream — same bytes, a route the server does not gate on
 *   that policy.
 * @param quality the *download quality* stamped on the row when the user tapped Download (M9).
 *   Anything but [DownloadQuality.ORIGINAL] replaces the media entry with a server-side transcode
 *   and nothing else — artwork, subtitles and trickplay tiles are the same files either way.
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
         */
        fun plan(
            item: BaseItemDto,
            directoryName: String,
            downloadAllowed: Boolean = true,
            quality: DownloadQuality = DownloadQuality.ORIGINAL,
        ): List<PlannedFile> {
            val mediaSource = item.mediaSources?.firstOrNull()
            val mediaSourceId = mediaSource?.id

            return buildList {
                primaryImage(item)?.let(::add)
                add(media(item, directoryName, mediaSourceId, downloadAllowed, quality))
                backdropImage(item)?.let(::add)
                seriesImage(item)?.let(::add)
                if (mediaSourceId != null) {
                    addAll(subtitles(item, mediaSourceId, mediaSource.mediaStreams.orEmpty()))
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
         * A transcode also renames the file: the bytes are `mp4` whatever the source container was,
         * and a `.mkv` holding an `mp4` is a file ExoPlayer sniffs its way out of but a user
         * plugging the tablet into a computer does not.
         */
        private fun media(
            item: BaseItemDto,
            directoryName: String,
            mediaSourceId: String?,
            downloadAllowed: Boolean,
            quality: DownloadQuality,
        ): PlannedFile =
            PlannedFile(
                type = DownloadFileType.MEDIA,
                fileName = DownloadPaths.mediaFileName(item, directoryName, quality),
                url =
                    when {
                        quality.isTranscoded -> urls.transcodedVideoUrl(item.id, mediaSourceId, quality)
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
         * External text subtitle tracks, one file each.
         *
         * Streams are selected by [MediaStream.isExternal] rather than by `deliveryMethod`: the
         * latter is only populated by playback-info negotiation, while a download works from a
         * plain item request. Bitmap formats (PGS, VobSub) are skipped because ExoPlayer cannot
         * play them from a standalone sidecar file — the same restriction jellyfin-android's
         * download engine applies.
         */
        private fun subtitles(
            item: BaseItemDto,
            mediaSourceId: String,
            streams: List<MediaStream>,
        ): List<PlannedFile> =
            streams
                .filter { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        stream.isExternal &&
                        stream.codec?.lowercase() in TEXT_SUBTITLE_CODECS
                }.map { stream ->
                    val format = SUBTITLE_FORMATS[stream.codec?.lowercase()] ?: DEFAULT_SUBTITLE_FORMAT
                    val language = stream.language?.takeIf { it.isNotBlank() } ?: UNDEFINED_LANGUAGE
                    PlannedFile(
                        type = DownloadFileType.SUBTITLE,
                        fileName = "subtitle.${stream.index}.$language.$format",
                        url = urls.subtitleUrl(item.id, mediaSourceId, stream.index, format),
                        streamIndex = stream.index,
                    )
                }

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

            /** ISO 639-2 "undetermined" — what a track with no declared language is filed under. */
            private const val UNDEFINED_LANGUAGE = "und"
        }
    }
