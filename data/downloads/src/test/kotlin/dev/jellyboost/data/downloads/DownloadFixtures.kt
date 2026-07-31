package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import java.time.Instant
import java.util.UUID

/**
 * Shared fixtures for the download pipeline's unit tests.
 *
 * The builders take a lot of defaulted parameters on purpose: every test then names only the two or
 * three fields it actually asserts on, which is what keeps the assertions readable.
 */
@Suppress("LongParameterList")
object DownloadFixtures {
    val NOW: Instant = Instant.parse("2026-07-28T12:00:00Z")

    /** Deterministic, readable ids — `uuid(1)` is `…-0001`. */
    fun uuid(seed: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    fun movie(
        id: UUID = uuid(1),
        name: String = "Arrival",
        year: Int? = 2016,
        path: String? = "/media/films/Arrival (2016)/Arrival.2016.mkv",
        mediaSourceId: String? = "source-1",
        sizeBytes: Long? = 2_100_000_000L,
        sourceBitRate: Int? = null,
        runTimeTicks: Long? = null,
        streams: List<MediaStream> = emptyList(),
        sourceContainer: String? = "mkv",
        defaultAudioStreamIndex: Int? = null,
        primaryTag: String? = "primary-tag",
        backdropTag: String? = null,
        trickplay: Map<String, Map<String, TrickplayInfoDto>>? = null,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.MOVIE,
            name = name,
            productionYear = year,
            path = path,
            container = "mkv",
            runTimeTicks = runTimeTicks,
            imageTags = primaryTag?.let { mapOf(ImageType.PRIMARY to it) },
            backdropImageTags = backdropTag?.let { listOf(it) },
            trickplay = trickplay,
            mediaSources =
                mediaSourceId?.let {
                    listOf(
                        mediaSource(
                            id = it,
                            size = sizeBytes,
                            bitrate = sourceBitRate,
                            streams = streams,
                            container = sourceContainer,
                            defaultAudioStreamIndex = defaultAudioStreamIndex,
                        ),
                    )
                },
        )

    @Suppress("LongParameterList")
    fun episode(
        id: UUID = uuid(2),
        seriesId: UUID? = uuid(10),
        seasonId: UUID? = uuid(11),
        seriesName: String? = "Westworld",
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 2,
        name: String = "Chestnut",
        seriesPrimaryImageTag: String? = "series-tag",
        runTimeTicks: Long? = null,
        sizeBytes: Long? = 1_000L,
        sourceBitRate: Int? = null,
        streams: List<MediaStream> = emptyList(),
        defaultAudioStreamIndex: Int? = null,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.EPISODE,
            name = name,
            seriesId = seriesId,
            seasonId = seasonId,
            seriesName = seriesName,
            parentIndexNumber = seasonNumber,
            indexNumber = episodeNumber,
            seriesPrimaryImageTag = seriesPrimaryImageTag,
            path = "/media/tv/Westworld/S01/Westworld.S01E02.mkv",
            container = "mkv",
            runTimeTicks = runTimeTicks,
            imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
            mediaSources =
                listOf(
                    mediaSource(
                        id = "source-2",
                        size = sizeBytes,
                        bitrate = sourceBitRate,
                        streams = streams,
                        defaultAudioStreamIndex = defaultAudioStreamIndex,
                    ),
                ),
        )

    /**
     * A season — a **folder**, which is the whole point of the fixture: it has no `mediaSources`,
     * and `isFolder` is what the server sends for one.
     */
    fun season(
        id: UUID = uuid(11),
        seriesId: UUID? = uuid(10),
        seriesName: String? = "Westworld",
        name: String = "Season 1",
        seasonNumber: Int? = 1,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.SEASON,
            name = name,
            seriesId = seriesId,
            seriesName = seriesName,
            indexNumber = seasonNumber,
            isFolder = true,
            imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
        )

    /** A series — the other folder a Download button can be tapped on. */
    fun series(
        id: UUID = uuid(10),
        name: String = "Westworld",
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.SERIES,
            name = name,
            isFolder = true,
            imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
        )

    /**
     * `MediaSourceInfo` has no defaulted constructor, so every fixture goes through this one
     * builder rather than repeating twenty irrelevant flags.
     */
    fun mediaSource(
        id: String,
        size: Long?,
        bitrate: Int? = null,
        streams: List<MediaStream> = emptyList(),
        container: String? = "mkv",
        defaultAudioStreamIndex: Int? = null,
    ): MediaSourceInfo =
        MediaSourceInfo(
            id = id,
            size = size,
            bitrate = bitrate,
            container = container,
            mediaStreams = streams,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            type = MediaSourceType.DEFAULT,
            protocol = MediaProtocol.FILE,
            isRemote = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            supportsTranscoding = true,
            supportsDirectStream = true,
            supportsDirectPlay = true,
            isInfiniteStream = false,
            useMostCompatibleTranscodingProfile = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            hasSegments = false,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
        )

    /**
     * A source video track — what the remux check reads (`DownloadEnqueuer.remuxBytes`).
     *
     * The three fields that matter each default to a value that *passes* the check at `HIGH`, so a
     * test that wants a condition to fail names only the one field it is failing.
     */
    fun videoStream(
        index: Int = 0,
        codec: String? = "h264",
        height: Int? = 1080,
        width: Int? = 1920,
        bitRate: Int? = 6_000_000,
    ): MediaStream =
        MediaStream(
            index = index,
            type = MediaStreamType.VIDEO,
            codec = codec,
            height = height,
            width = width,
            bitRate = bitRate,
            isExternal = false,
            isInterlaced = false,
            isDefault = true,
            isForced = false,
            isHearingImpaired = false,
            isTextSubtitleStream = false,
            supportsExternalStream = false,
        )

    /**
     * A subtitle stream.
     *
     * [external] and [supportsExternalStream] are separate parameters because they answer separate
     * questions, and conflating them is what hid a whole class of downloadable subtitles: the first
     * is *"is this already a file next to the video?"*, the second is the server's *"I will extract
     * this on demand"*, which it says for embedded SRTs too (the Élémentaire finding in
     * docs/notes/offline-multitrack-design.md). It defaults to `true` for that reason — a real text
     * subtitle stream advertises it whether or not it is external.
     */
    fun subtitleStream(
        index: Int,
        codec: String = "subrip",
        language: String? = "eng",
        external: Boolean = true,
        supportsExternalStream: Boolean = true,
    ): MediaStream =
        MediaStream(
            index = index,
            type = MediaStreamType.SUBTITLE,
            codec = codec,
            language = language,
            isExternal = external,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isTextSubtitleStream = true,
            supportsExternalStream = supportsExternalStream,
        )

    /** A source audio track — what the baked-audio pin is chosen from (schema v8). */
    fun audioStream(
        index: Int,
        language: String? = "eng",
        codec: String? = "ac3",
    ): MediaStream =
        MediaStream(
            index = index,
            type = MediaStreamType.AUDIO,
            codec = codec,
            language = language,
            isExternal = false,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isTextSubtitleStream = false,
            supportsExternalStream = false,
        )

    fun download(
        itemId: UUID = uuid(1),
        status: DownloadStatus = DownloadStatus.QUEUED,
        queuePosition: Int = 0,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        projectedBytes: Long? = null,
        sizeIsExact: Boolean = false,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        attemptCount: Int = 0,
        bakedAudioStreamIndex: Int? = null,
        directoryName: String = "Arrival (2016)",
        itemName: String = "Arrival",
        seriesName: String? = null,
        updatedAt: Instant = NOW,
    ): DownloadEntity =
        DownloadEntity(
            itemId = itemId,
            userId = uuid(99),
            status = status,
            mediaSourceId = "source-1",
            quality = quality,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            projectedBytes = projectedBytes,
            sizeIsExact = sizeIsExact,
            bakedAudioStreamIndex = bakedAudioStreamIndex,
            queuePosition = queuePosition,
            attemptCount = attemptCount,
            directoryName = directoryName,
            itemName = itemName,
            seriesName = seriesName,
            createdAt = NOW,
            updatedAt = updatedAt,
        )

    fun file(
        id: Long,
        itemId: UUID = uuid(1),
        type: DownloadFileType = DownloadFileType.MEDIA,
        fileName: String = "Arrival.2016.mkv",
        url: String = "https://server/Items/x/Download",
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        status: DownloadStatus = DownloadStatus.QUEUED,
        path: String = "/tmp/$fileName",
        streamIndex: Int? = null,
        tileIndex: Int? = null,
        tileWidth: Int? = null,
    ): DownloadFileEntity =
        DownloadFileEntity(
            id = id,
            itemId = itemId,
            type = type,
            streamIndex = streamIndex,
            tileIndex = tileIndex,
            tileWidth = tileWidth,
            fileName = fileName,
            path = path,
            url = url,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            status = status,
        )

    /** The server's trickplay description for one thumbnail width (M8 offline trickplay). */
    fun trickplayInfo(
        width: Int = 320,
        height: Int = 180,
        tileWidth: Int = 10,
        tileHeight: Int = 10,
        thumbnailCount: Int = 250,
        interval: Int = 10_000,
    ): TrickplayInfoDto =
        TrickplayInfoDto(
            width = width,
            height = height,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            thumbnailCount = thumbnailCount,
            interval = interval,
            bandwidth = 0,
        )
}
