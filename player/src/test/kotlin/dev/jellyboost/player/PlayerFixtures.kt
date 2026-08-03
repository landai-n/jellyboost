package dev.jellyboost.player

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.data.downloads.offline.DownloadedAudio
import dev.jellyboost.data.downloads.offline.DownloadedMedia
import dev.jellyboost.data.downloads.offline.DownloadedSubtitle
import dev.jellyboost.data.downloads.offline.DownloadedTrickplay
import dev.jellyboost.player.model.ExternalAudio
import dev.jellyboost.player.model.ExternalSubtitle
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.LocalTrickplay
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

/** Shared builders for the `:player` tests. Only the fields a test actually asserts on are named. */
internal object PlayerFixtures {
    val ITEM_ID: UUID = UUID.fromString("0b3d5f6a-1c2e-4a7b-9d8c-5e4f3a2b1c0d")

    /** The same id the server would echo back, dash-less as it appears in a request. */
    const val DASHLESS_ITEM_ID = "0b3d5f6a1c2e4a7b9d8c5e4f3a2b1c0d"

    const val PLAY_SESSION_ID = "session-1"

    /** Two hours in Jellyfin ticks. */
    const val RUN_TIME_TICKS = 72_000_000_000L

    @Suppress("LongParameterList")
    fun mediaSourceInfo(
        id: String = ITEM_ID.toString(),
        supportsDirectPlay: Boolean = false,
        supportsDirectStream: Boolean = false,
        supportsTranscoding: Boolean = false,
        transcodingUrl: String? = null,
        transcodingSubProtocol: MediaStreamProtocol = MediaStreamProtocol.HLS,
        container: String? = "mkv",
        protocol: MediaProtocol = MediaProtocol.FILE,
        path: String? = "/media/movie.mkv",
        runTimeTicks: Long? = RUN_TIME_TICKS,
        mediaStreams: List<MediaStream> = emptyList(),
        defaultAudioStreamIndex: Int? = null,
        defaultSubtitleStreamIndex: Int? = null,
    ): MediaSourceInfo =
        MediaSourceInfo(
            id = id,
            type = MediaSourceType.DEFAULT,
            protocol = protocol,
            path = path,
            container = container,
            runTimeTicks = runTimeTicks,
            supportsDirectPlay = supportsDirectPlay,
            supportsDirectStream = supportsDirectStream,
            supportsTranscoding = supportsTranscoding,
            transcodingUrl = transcodingUrl,
            transcodingSubProtocol = transcodingSubProtocol,
            mediaStreams = mediaStreams,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
            isRemote = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            isInfiniteStream = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            hasSegments = false,
        )

    fun playbackInfoResponse(
        sources: List<MediaSourceInfo>,
        playSessionId: String? = PLAY_SESSION_ID,
    ): PlaybackInfoResponse = PlaybackInfoResponse(mediaSources = sources, playSessionId = playSessionId)

    @Suppress("LongParameterList")
    fun audioStream(
        index: Int,
        language: String? = "eng",
        codec: String = "ac3",
        displayTitle: String = "English - AC3",
        isExternal: Boolean = false,
    ): MediaStream =
        MediaStream(
            type = MediaStreamType.AUDIO,
            index = index,
            codec = codec,
            language = language,
            displayTitle = displayTitle,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isExternal = isExternal,
            isTextSubtitleStream = false,
            supportsExternalStream = false,
        )

    @Suppress("LongParameterList")
    fun subtitleStream(
        index: Int,
        codec: String = "srt",
        language: String? = "eng",
        displayTitle: String = "English",
        deliveryMethod: SubtitleDeliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
        deliveryUrl: String? = "/Videos/1/Subtitles/$index/Stream.srt",
        isExternal: Boolean = true,
    ): MediaStream =
        MediaStream(
            type = MediaStreamType.SUBTITLE,
            index = index,
            codec = codec,
            language = language,
            displayTitle = displayTitle,
            deliveryMethod = deliveryMethod,
            deliveryUrl = deliveryUrl,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isExternal = isExternal,
            isTextSubtitleStream = true,
            supportsExternalStream = true,
        )

    @Suppress("LongParameterList")
    fun remoteSource(
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
        protocol: MediaProtocol = MediaProtocol.FILE,
        container: String? = "mkv",
        path: String? = "/media/movie.mkv",
        transcodingUrl: String? = null,
        transcodingSubProtocol: MediaStreamProtocol? = null,
        maxStreamingBitrate: Int? = null,
        startPositionTicks: Long = 0L,
        audioTracks: List<PlaybackTrack> = emptyList(),
        subtitleTracks: List<PlaybackTrack> = emptyList(),
        externalSubtitles: List<ExternalSubtitle> = emptyList(),
        selectedAudioIndex: Int? = null,
        selectedSubtitleIndex: Int? = null,
    ): RemotePlaybackMediaSource =
        RemotePlaybackMediaSource(
            itemId = ITEM_ID,
            mediaSourceId = ITEM_ID.toString(),
            playSessionId = PLAY_SESSION_ID,
            playMethod = playMethod,
            container = container,
            protocol = protocol,
            path = path,
            transcodingUrl = transcodingUrl,
            transcodingSubProtocol = transcodingSubProtocol,
            liveStreamId = null,
            maxStreamingBitrate = maxStreamingBitrate,
            runTimeTicks = RUN_TIME_TICKS,
            startPositionTicks = startPositionTicks,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            externalSubtitles = externalSubtitles,
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
        )

    // ---- M8, offline ---------------------------------------------------------------------------

    /** `file://` URI of the downloaded media file used throughout the offline tests. */
    const val LOCAL_MEDIA_URI = "file:///downloads/Arrival%20(2016)/Arrival.mkv"

    @Suppress("LongParameterList")
    fun localSource(
        startPositionTicks: Long = 0L,
        audioTracks: List<PlaybackTrack> = emptyList(),
        subtitleTracks: List<PlaybackTrack> = emptyList(),
        externalSubtitles: List<ExternalSubtitle> = emptyList(),
        externalAudio: List<ExternalAudio> = emptyList(),
        allAudioTracks: List<PlaybackTrack> = audioTracks,
        allSubtitleTracks: List<PlaybackTrack> = subtitleTracks,
        selectedAudioIndex: Int? = null,
        selectedSubtitleIndex: Int? = null,
        trickplay: LocalTrickplay? = null,
    ): LocalPlaybackMediaSource =
        LocalPlaybackMediaSource(
            itemId = ITEM_ID,
            mediaSourceId = ITEM_ID.toString(),
            mediaUri = LOCAL_MEDIA_URI,
            runTimeTicks = RUN_TIME_TICKS,
            startPositionTicks = startPositionTicks,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            externalSubtitles = externalSubtitles,
            externalAudio = externalAudio,
            allAudioTracks = allAudioTracks,
            allSubtitleTracks = allSubtitleTracks,
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
            trickplay = trickplay,
        )

    /**
     * A transcoded download of the Élémentaire repro item, as `LocalPlaybackResolver` builds it.
     *
     * The file on disk holds one AAC track (the French VFF the download baked in) and the two
     * subtitles whose sidecars came with it; the *source* has three audio tracks and four
     * subtitles. That gap is the whole subject of the connectivity-aware pickers, so it is worth
     * having in one place rather than re-listed per test.
     */
    fun downloadedFilm(
        selectedAudioIndex: Int? = BAKED_AUDIO_INDEX,
        selectedSubtitleIndex: Int? = null,
    ): LocalPlaybackMediaSource =
        localSource(
            audioTracks = listOf(track(BAKED_AUDIO_INDEX, "French VFF", "fra")),
            subtitleTracks =
                listOf(track(0, "English", "eng", external = true), track(1, "French", "fra", external = true)),
            allAudioTracks =
                listOf(
                    track(BAKED_AUDIO_INDEX, "French VFF", "fra"),
                    track(4, "French VFQ", "fra"),
                    track(STREAMED_AUDIO_INDEX, "English VO", "eng"),
                ),
            allSubtitleTracks =
                listOf(
                    track(0, "English", "eng", external = true),
                    track(1, "French", "fra", external = true),
                    track(6, "French forced", "fra"),
                    track(STREAMED_SUBTITLE_INDEX, "French full", "fra"),
                ),
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
        )

    /** The audio stream the transcoded download baked into the file — the only one it can play. */
    const val BAKED_AUDIO_INDEX = 3

    /** An audio stream of the source the download dropped; only the server can supply it. */
    const val STREAMED_AUDIO_INDEX = 5

    /** An embedded subtitle whose sidecar was never fetched; likewise server-only. */
    const val STREAMED_SUBTITLE_INDEX = 7

    private fun track(
        index: Int,
        label: String,
        language: String,
        external: Boolean = false,
    ): PlaybackTrack =
        PlaybackTrack(index = index, label = label, language = language, codec = "ac3", isExternal = external)

    /** What `DownloadedMediaProvider` hands `LocalPlaybackResolver`. */
    @Suppress("LongParameterList")
    fun downloadedMedia(
        mediaSource: MediaSourceInfo? = mediaSourceInfo(supportsDirectPlay = true),
        runTimeTicks: Long = RUN_TIME_TICKS,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        bakedAudioStreamIndex: Int? = null,
        subtitles: List<DownloadedSubtitle> = emptyList(),
        audio: List<DownloadedAudio> = emptyList(),
        trickplay: DownloadedTrickplay? = null,
    ): DownloadedMedia =
        DownloadedMedia(
            itemId = ITEM_ID,
            mediaSourceId = ITEM_ID.toString(),
            mediaSource = mediaSource,
            mediaUri = LOCAL_MEDIA_URI,
            runTimeTicks = runTimeTicks,
            quality = quality,
            bakedAudioStreamIndex = bakedAudioStreamIndex,
            subtitles = subtitles,
            audio = audio,
            trickplay = trickplay,
        )

    @Suppress("LongParameterList")
    fun downloadedTrickplay(
        width: Int = 320,
        tileWidth: Int = 10,
        tileHeight: Int = 10,
        thumbnailCount: Int = 250,
        intervalMs: Int = 10_000,
        tileUris: List<String> = listOf("file:///downloads/t.0.jpg", "file:///downloads/t.1.jpg"),
    ): DownloadedTrickplay =
        DownloadedTrickplay(
            width = width,
            height = 180,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            thumbnailCount = thumbnailCount,
            intervalMs = intervalMs,
            tileUris = tileUris,
        )
}
