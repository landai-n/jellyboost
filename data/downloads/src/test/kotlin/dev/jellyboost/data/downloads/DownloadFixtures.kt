package dev.jellyboost.data.downloads

import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.TransactionRunner
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
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import java.time.Instant
import java.util.UUID

@Suppress("LongParameterList")
object DownloadFixtures {
    val NOW: Instant = Instant.parse("2026-07-28T12:00:00Z")

    /**
     * A [TransactionRunner] that just runs the block — the stand-in for every test that is *not* about
     * atomicity itself.
     */
    val directTransactionRunner =
        object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }

    /**
     * [directTransactionRunner] that also reports whether a transaction is open *right now*, which is
     * what lets a test assert that two DAO calls are inside the **same** transaction rather than merely
     * both present. Nesting is counted the way Room joins it: an inner `inTransaction` is not a second.
     */
    class RecordingTransactionRunner : TransactionRunner {
        private var depth = 0

        /** How many outermost transactions have been opened. */
        var count = 0
            private set

        val isOpen: Boolean get() = depth > 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            if (depth == 0) count++
            depth++
            return try {
                block()
            } finally {
                depth--
            }
        }
    }

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

    /** A season — a **folder**: it has no `mediaSources`, and `isFolder` is what the server sends. */
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

    // ---- music --------------------------------------------------------------------------------

    /**
     * A music track. The three ids are what the whole music download path turns on: [albumId] is the
     * folder it was expanded from and the artwork's address, [albumArtistId] is what the offline artist
     * page queries, and [albumPrimaryImageTag] is the cover the plan fetches.
     */
    fun track(
        id: UUID = uuid(30),
        name: String = "Go Your Own Way",
        albumId: UUID? = uuid(40),
        album: String? = "Rumours",
        albumArtistId: UUID? = uuid(50),
        albumArtist: String? = "Fleetwood Mac",
        artists: List<String>? = null,
        trackNumber: Int? = 4,
        discNumber: Int? = null,
        albumPrimaryImageTag: String? = "album-tag",
        primaryTag: String? = null,
        path: String? = "/media/music/Fleetwood Mac/Rumours/04 - Go Your Own Way.flac",
        container: String? = "flac",
        sizeBytes: Long? = 32_000_000L,
        runTimeTicks: Long? = 21_000_000_000L,
        streams: List<MediaStream> = emptyList(),
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.AUDIO,
            name = name,
            album = album,
            albumId = albumId,
            albumArtist = albumArtist,
            albumArtists = albumArtistId?.let { listOf(NameGuidPair(id = it, name = albumArtist)) },
            artists = artists,
            albumPrimaryImageTag = albumPrimaryImageTag,
            indexNumber = trackNumber,
            parentIndexNumber = discNumber,
            parentId = albumId,
            path = path,
            container = container,
            runTimeTicks = runTimeTicks,
            imageTags = primaryTag?.let { mapOf(ImageType.PRIMARY to it) },
            mediaSources =
                listOf(mediaSource(id = "source-$id", size = sizeBytes, streams = streams, container = container)),
        )

    /** A music album — a **folder**, like [season], so a tap on it has to expand. */
    fun album(
        id: UUID = uuid(40),
        name: String = "Rumours",
        artistId: UUID? = uuid(50),
        artistName: String? = "Fleetwood Mac",
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.MUSIC_ALBUM,
            name = name,
            albumArtist = artistName,
            albumArtists = artistId?.let { listOf(NameGuidPair(id = it, name = artistName)) },
            isFolder = true,
            imageTags = mapOf(ImageType.PRIMARY to "album-tag"),
        )

    /** A music artist — the other music folder a Download button can be tapped on. */
    fun artist(
        id: UUID = uuid(50),
        name: String = "Fleetwood Mac",
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.MUSIC_ARTIST,
            name = name,
            isFolder = true,
            imageTags = mapOf(ImageType.PRIMARY to "artist-tag"),
        )

    /** A playlist — a folder whose members are ordered by the playlist, not by the library. */
    fun playlist(
        id: UUID = uuid(60),
        name: String = "Road trip",
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.PLAYLIST,
            name = name,
            isFolder = true,
            imageTags = mapOf(ImageType.PRIMARY to "playlist-tag"),
        )

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
     * A source video track — what the remux check reads. The three fields that matter each default to a
     * value that *passes* the check at `HIGH`, so a test that wants a condition to fail names only the
     * one field it is failing.
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
     * A subtitle stream. [external] and [supportsExternalStream] are separate parameters because they
     * answer separate questions, and conflating them hid a whole class of downloadable subtitles: the
     * first is *"is this already a file next to the video?"*, the second is the server's *"I will
     * extract this on demand"*, which it says for embedded SRTs too — hence the `true` default.
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

    /** A source audio track — what the baked-audio pin is chosen from. */
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
        itemType: ItemType? = null,
        seriesName: String? = null,
        albumName: String? = null,
        artistName: String? = null,
        groupId: UUID? = null,
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
            itemType = itemType,
            seriesName = seriesName,
            albumName = albumName,
            artistName = artistName,
            groupId = groupId,
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

    /** The server's trickplay description for one thumbnail width. */
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
