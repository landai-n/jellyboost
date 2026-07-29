package dev.jellyfinnative.data.downloads.plan

import dev.jellyfinnative.core.common.model.DownloadQuality
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every URL the download pipeline can fetch, behind one seam.
 *
 * The file plan is the piece of this milestone most likely to be wrong in a way that only shows up
 * as a 404 halfway through a 2 GB transfer, so it is unit-tested — and it can only be unit-tested
 * if the URL builders are injectable. The SDK's builders are ordinary functions on a real
 * `ApiClient` with a real base URL, which a JVM test does not have.
 */
interface DownloadUrlFactory {
    /**
     * The dedicated download endpoint (`/Items/{id}/Download`).
     *
     * Preferred over a stream URL because it serves the original file untouched — no remuxing, no
     * server-side work — which is the only way the downloaded bytes match the source exactly.
     */
    fun mediaUrl(itemId: UUID): String

    /**
     * Fallback for a user whose policy has `enableContentDownloading` off: the static video stream,
     * which is the same bytes over a different route (docs/PLAN.md, "Download pipeline" → File
     * plan). Checked once at M1 for this project's server; the fallback exists so the pipeline does
     * not simply stop working for a differently-configured account.
     */
    fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ): String

    /**
     * A server-side transcode of the item, muxed into one progressive `.mkv` (M9 download quality).
     *
     * Every encoding parameter is spelled out rather than left to the server's own negotiation:
     * a download has no `PlaybackInfo` session behind it and no device profile to reason from, so
     * the only way the bytes are predictable is to ask for exactly one shape — H.264 video under
     * [DownloadQuality.videoBitRate] and [DownloadQuality.maxHeight], stereo AAC audio, and
     * [DownloadQuality.CONTAINER] (Matroska, for the reason spelled out on that constant: an mp4
     * muxed on the fly is a file Media3 refuses to open).
     *
     * `context = STATIC` is the one non-obvious parameter and it matters: the server throttles a
     * `STREAMING` transcode to roughly real time, which would make a two-hour film take two hours
     * to download. `STATIC` is the "produce this as fast as you can" context.
     *
     * @param quality must be a transcoded step; [DownloadQuality.ORIGINAL] has no stream URL and is
     *   served by [mediaUrl] instead.
     */
    fun transcodedVideoUrl(
        itemId: UUID,
        mediaSourceId: String?,
        quality: DownloadQuality,
    ): String

    /** An item image at a fixed width — artwork is stored small, not at source resolution. */
    fun imageUrl(
        itemId: UUID,
        imageType: ImageType,
        tag: String,
        fillWidth: Int,
    ): String

    /** One external subtitle stream, converted to [format] by the server. */
    fun subtitleUrl(
        itemId: UUID,
        mediaSourceId: String,
        streamIndex: Int,
        format: String,
    ): String

    /** One trickplay tile sheet at the given resolution. */
    fun trickplayTileUrl(
        itemId: UUID,
        width: Int,
        tileIndex: Int,
    ): String
}

/** [DownloadUrlFactory] on the SDK's own URL builders. */
@Singleton
internal class SdkDownloadUrlFactory
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : DownloadUrlFactory {
        override fun mediaUrl(itemId: UUID): String = apiClient.libraryApi.getDownloadUrl(itemId)

        override fun videoStreamUrl(
            itemId: UUID,
            mediaSourceId: String?,
        ): String =
            apiClient.videosApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
            )

        override fun transcodedVideoUrl(
            itemId: UUID,
            mediaSourceId: String?,
            quality: DownloadQuality,
        ): String =
            apiClient.videosApi.getVideoStreamByContainerUrl(
                itemId = itemId,
                // `/Videos/{id}/stream.mkv` rather than `?container=mkv`: the extension is part of
                // the path, so the response is one progressive file and not an HLS playlist.
                container = DownloadQuality.CONTAINER,
                static = false,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
                videoCodec = DownloadQuality.VIDEO_CODEC,
                audioCodec = DownloadQuality.AUDIO_CODEC,
                videoBitRate = quality.videoBitRate,
                maxHeight = quality.maxHeight,
                audioBitRate = DownloadQuality.AUDIO_BITRATE,
                maxAudioChannels = DownloadQuality.AUDIO_CHANNELS,
                // Copy the video track when it already fits the request, re-encode when it does
                // not: a 1080p H.264 file asked for at HIGH becomes a remux, which is free.
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = false,
                context = EncodingContext.STATIC,
            )

        override fun imageUrl(
            itemId: UUID,
            imageType: ImageType,
            tag: String,
            fillWidth: Int,
        ): String =
            apiClient.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = imageType,
                tag = tag,
                // WEBP at a capped width: a poster the offline UI draws at ~150 dp does not need
                // the 2000-pixel original, and artwork is downloaded before the media file.
                format = ImageFormat.WEBP,
                fillWidth = fillWidth,
            )

        override fun subtitleUrl(
            itemId: UUID,
            mediaSourceId: String,
            streamIndex: Int,
            format: String,
        ): String =
            apiClient.subtitleApi.getSubtitleUrl(
                routeItemId = itemId,
                routeMediaSourceId = mediaSourceId,
                routeIndex = streamIndex,
                routeFormat = format,
            )

        override fun trickplayTileUrl(
            itemId: UUID,
            width: Int,
            tileIndex: Int,
        ): String =
            apiClient.trickplayApi.getTrickplayTileImageUrl(
                itemId = itemId,
                width = width,
                index = tileIndex,
            )
    }
