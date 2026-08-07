package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadQuality
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
internal interface DownloadUrlFactory {
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
     * @param audioStreamIndex the absolute `MediaStream.index` of the single audio track to encode.
     *   The endpoint takes exactly one and the transcoder drops every other track, so naming it is
     *   the difference between "the track the user wanted" and "whatever the server chose"
     *   (see [downloadAudioStreamIndex]). `null` omits the parameter, which is what an item with no
     *   audio streams needs — sending an index that names nothing is a request the server has no
     *   sensible answer for.
     */
    fun transcodedVideoUrl(
        itemId: UUID,
        mediaSourceId: String?,
        quality: DownloadQuality,
        audioStreamIndex: Int?,
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

    /**
     * One extra audio language of a transcoded download, fetched as a video+audio
     * [DownloadQuality.AUDIO_FETCH_CONTAINER] and stripped locally to
     * [DownloadQuality.AUDIO_SIDECAR_CONTAINER].
     *
     * The video track is not wanted, but the request cannot leave it out: server 10.11 hard-codes
     * `audioStreamIndex` to `null` on the audio-only endpoint (`EncodingHelper.AttachMediaSourceInfo`),
     * so asking for a *specific* extra track only works through `/Videos`, which does honor it. The
     * video that comes back is deliberately as cheap as the server will make it
     * ([DownloadQuality.AUDIO_FETCH_VIDEO_BITRATE] and friends) and is thrown away by a local
     * Transformer strip once the fetch lands — see [DownloadQuality.AUDIO_SIDECAR_CONTAINER]'s KDoc
     * for why that strip is what finally gives the sidecar a complete `moov`.
     *
     * @param streamIndex the absolute `MediaStream.index` of the audio track to fetch — never the
     *   track a sibling [transcodedVideoUrl] call already baked into the media file.
     */
    fun audioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        streamIndex: Int,
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
            audioStreamIndex: Int?,
        ): String =
            apiClient.videosApi.getVideoStreamByContainerUrl(
                itemId = itemId,
                // `/Videos/{id}/stream.mkv` rather than `?container=mkv`: the extension is part of
                // the path, so the response is one progressive file and not an HLS playlist.
                container = DownloadQuality.CONTAINER,
                static = false,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
                // A fresh id per URL. Nothing stores it yet — it costs one query parameter and it
                // is what a later `stopEncodingProcess` (or any correlation with the server's own
                // transcode reporting) would need to name this encode. Without it the server
                // invents one we never learn.
                playSessionId = UUID.randomUUID().toString(),
                videoCodec = DownloadQuality.VIDEO_CODEC,
                audioCodec = DownloadQuality.AUDIO_CODEC,
                // The one track the muxed file will hold. Omitted (`null`) the server picks the
                // source's default, which is the same track — but nothing would have *recorded*
                // which one that was, and offline there is no server left to ask.
                audioStreamIndex = audioStreamIndex,
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

        override fun audioStreamUrl(
            itemId: UUID,
            mediaSourceId: String?,
            streamIndex: Int,
        ): String =
            apiClient.videosApi.getVideoStreamByContainerUrl(
                itemId = itemId,
                // `/Videos`, not `/Audio`: the audio-only endpoint ignores audioStreamIndex on 10.11
                // (see the interface KDoc), so a specific extra track can only be named here.
                container = DownloadQuality.AUDIO_FETCH_CONTAINER,
                static = false,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
                // A fresh id per URL, same reasoning as transcodedVideoUrl's.
                playSessionId = UUID.randomUUID().toString(),
                audioCodec = DownloadQuality.AUDIO_CODEC,
                // The one track this sidecar is for — the whole point of routing through /Videos.
                audioStreamIndex = streamIndex,
                audioBitRate = DownloadQuality.AUDIO_BITRATE,
                maxAudioChannels = DownloadQuality.AUDIO_CHANNELS,
                // Junk video, as cheap as the server will make it — stripped locally once fetched.
                videoCodec = DownloadQuality.VIDEO_CODEC,
                videoBitRate = DownloadQuality.AUDIO_FETCH_VIDEO_BITRATE,
                maxFramerate = DownloadQuality.AUDIO_FETCH_MAX_FRAMERATE,
                maxHeight = DownloadQuality.AUDIO_FETCH_MAX_HEIGHT,
                maxWidth = DownloadQuality.AUDIO_FETCH_MAX_WIDTH,
                // Neither track may be copied: a copy could carry every other audio track along with
                // it, and the video must actually shrink to the junk shape above rather than pass the
                // source through.
                allowVideoStreamCopy = false,
                allowAudioStreamCopy = false,
                context = EncodingContext.STATIC,
            )
    }
