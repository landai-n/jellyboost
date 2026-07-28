package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
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
        streams: List<MediaStream> = emptyList(),
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
            imageTags = primaryTag?.let { mapOf(ImageType.PRIMARY to it) },
            backdropImageTags = backdropTag?.let { listOf(it) },
            trickplay = trickplay,
            mediaSources =
                mediaSourceId?.let { listOf(mediaSource(id = it, size = sizeBytes, streams = streams)) },
        )

    fun episode(
        id: UUID = uuid(2),
        seriesId: UUID? = uuid(10),
        seasonId: UUID? = uuid(11),
        seriesName: String? = "Westworld",
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 2,
        name: String = "Chestnut",
        seriesPrimaryImageTag: String? = "series-tag",
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
            imageTags = mapOf(ImageType.PRIMARY to "primary-tag"),
            mediaSources = listOf(mediaSource(id = "source-2", size = 1_000L)),
        )

    /**
     * `MediaSourceInfo` has no defaulted constructor, so every fixture goes through this one
     * builder rather than repeating twenty irrelevant flags.
     */
    fun mediaSource(
        id: String,
        size: Long?,
        streams: List<MediaStream> = emptyList(),
    ): MediaSourceInfo =
        MediaSourceInfo(
            id = id,
            size = size,
            mediaStreams = streams,
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

    fun subtitleStream(
        index: Int,
        codec: String = "subrip",
        language: String? = "eng",
        external: Boolean = true,
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
            supportsExternalStream = external,
        )

    fun download(
        itemId: UUID = uuid(1),
        status: DownloadStatus = DownloadStatus.QUEUED,
        queuePosition: Int = 0,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        directoryName: String = "Arrival (2016)",
        itemName: String = "Arrival",
        seriesName: String? = null,
    ): DownloadEntity =
        DownloadEntity(
            itemId = itemId,
            userId = uuid(99),
            status = status,
            mediaSourceId = "source-1",
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            queuePosition = queuePosition,
            directoryName = directoryName,
            itemName = itemName,
            seriesName = seriesName,
            createdAt = NOW,
            updatedAt = NOW,
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
    ): DownloadFileEntity =
        DownloadFileEntity(
            id = id,
            itemId = itemId,
            type = type,
            fileName = fileName,
            path = "/tmp/$fileName",
            url = url,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            status = status,
        )
}
