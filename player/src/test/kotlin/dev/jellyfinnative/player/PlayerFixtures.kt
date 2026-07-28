package dev.jellyfinnative.player

import dev.jellyfinnative.player.model.ExternalSubtitle
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
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

    fun audioStream(
        index: Int,
        language: String? = "eng",
        codec: String = "ac3",
        displayTitle: String = "English - AC3",
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
            isExternal = false,
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
}
