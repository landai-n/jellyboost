package dev.jellyfinnative.player.api

import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
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

    // M9 -------------------------------------------------------------------------------------------

    /**
     * The item's trickplay geometry — `BaseItemDto.trickplay`, keyed by media source id and then by
     * thumbnail width.
     *
     * `PlaybackInfo` does not carry it, so the scrubber has to ask for the item itself. Empty when
     * the server generated no scrubbing thumbnails for this item, which is the common case for a
     * freshly added library.
     */
    suspend fun getTrickplayInfo(itemId: UUID): Map<String, Map<String, TrickplayInfoDto>>

    /**
     * `GET /MediaSegments/{itemId}` — the intro/outro ranges a plugin detected.
     *
     * Server-only by definition (docs/PLAN.md, "Playback pipeline" → "Media segments (M9)"), and
     * optional: a server without the Media Segments API, or without a provider plugin, answers 404
     * or with nothing, and the feature is simply absent rather than broken.
     */
    suspend fun getMediaSegments(
        itemId: UUID,
        types: Collection<MediaSegmentType>,
    ): List<MediaSegmentDto>
}
