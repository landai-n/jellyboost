package dev.jellyboost.player.api

import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import java.util.UUID

/** An interface because the SDK's `ApiClient` is an abstract class whose operations are extension properties. */
internal interface PlayerApi {
    /** `null` only if the SDK has no DeviceInfo. */
    val deviceId: String?

    /** `POST /Items/{itemId}/PlaybackInfo` — negotiates the play method for one item. */
    suspend fun getPlaybackInfo(
        itemId: UUID,
        request: PlaybackInfoDto,
    ): PlaybackInfoResponse

    /** `POST /Sessions/Playing` */
    suspend fun reportPlaybackStart(info: PlaybackStartInfo)

    /** `POST /Sessions/Playing/Progress` */
    suspend fun reportPlaybackProgress(info: PlaybackProgressInfo)

    /** `POST /Sessions/Playing/Stopped` */
    suspend fun reportPlaybackStopped(info: PlaybackStopInfo)

    /** `DELETE /Videos/ActiveEncodings` — skipping it leaves an orphaned ffmpeg process on the server. */
    suspend fun stopEncodingProcess(
        deviceId: String,
        playSessionId: String,
    )

    /**
     * `BaseItemDto.trickplay`, keyed by media source id then thumbnail width. `PlaybackInfo` does not carry it,
     * so the item itself has to be fetched; empty whenever the server generated no thumbnails.
     */
    suspend fun getTrickplayInfo(itemId: UUID): Map<String, Map<String, TrickplayInfoDto>>

    /**
     * `GET /MediaSegments/{itemId}` — intro/outro ranges. Optional: a server without the API or without a
     * provider plugin answers 404 or nothing, and the feature is absent rather than broken.
     */
    suspend fun getMediaSegments(
        itemId: UUID,
        types: Collection<MediaSegmentType>,
    ): List<MediaSegmentDto>

    /**
     * `GET /Playback/BitrateTest` — [size] bytes of zeroes, worth nothing but the time they took to arrive.
     * The caller keeps [size] small enough that the returned array is never a memory problem.
     */
    suspend fun getBitrateTestBytes(size: Int): ByteArray
}
