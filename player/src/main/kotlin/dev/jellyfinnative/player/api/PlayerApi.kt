package dev.jellyfinnative.player.api

import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.util.UUID

/**
 * The server calls playback needs, behind one seam.
 *
 * Same reason `:core:network` has `JellyfinApiFacade`: the SDK's `ApiClient` is an abstract class
 * whose operation objects are created through extension properties, which makes it awkward to fake.
 * With this interface the resolver and the reporter — the two classes this milestone must test
 * densely — are plain objects with a mockable dependency.
 */
interface PlayerApi {
    /** Device id the server attributes this session to; `null` only if the SDK has no DeviceInfo. */
    val deviceId: String?

    /** `POST /Items/{itemId}/PlaybackInfo` — negotiates the play method for one item. */
    suspend fun getPlaybackInfo(
        itemId: UUID,
        request: PlaybackInfoDto,
    ): PlaybackInfoResponse

    /** `POST /Sessions/Playing` — playback began. */
    suspend fun reportPlaybackStart(info: PlaybackStartInfo)

    /** `POST /Sessions/Playing/Progress` — periodic position/pause report. */
    suspend fun reportPlaybackProgress(info: PlaybackProgressInfo)

    /** `POST /Sessions/Playing/Stopped` — playback ended, for whatever reason. */
    suspend fun reportPlaybackStopped(info: PlaybackStopInfo)

    /**
     * `DELETE /Videos/ActiveEncodings` — kills the server-side ffmpeg process for this session.
     *
     * Skipping this is what leaves orphaned transcodes behind on the server, which the M5
     * definition of done explicitly checks for.
     */
    suspend fun stopEncodingProcess(
        deviceId: String,
        playSessionId: String,
    )
}
