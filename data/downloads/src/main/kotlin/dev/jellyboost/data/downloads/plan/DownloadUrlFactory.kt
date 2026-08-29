package dev.jellyboost.data.downloads.plan

import dev.jellyboost.core.common.model.DownloadQuality
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.videoAttachmentsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Every URL the download pipeline can fetch, behind a seam a JVM test can build without a base URL. */
internal interface DownloadUrlFactory {
    /** `/Items/{id}/Download` — the original file untouched, the only route whose bytes match the source. */
    fun mediaUrl(itemId: UUID): String

    /** Fallback when the user's `enableContentDownloading` policy is off: the same bytes, another route. */
    fun videoStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ): String

    /**
     * [videoStreamUrl]'s audio counterpart. `static=true` is what makes it the original bytes, which is
     * what a music download is. Routing through `/Audio` rather than `/Videos` is safe here: the
     * "/Videos not /Audio" rule is about `audioStreamIndex`, and this names no stream index at all.
     */
    fun staticAudioUrl(
        itemId: UUID,
        mediaSourceId: String?,
    ): String

    /**
     * A server-side transcode of the item, muxed into one progressive `.mkv`.
     *
     * Every encoding parameter is spelled out rather than negotiated: a download has no `PlaybackInfo`
     * session behind it and no device profile to reason from, so asking for exactly one shape is the
     * only way the bytes are predictable.
     *
     * `context = STATIC` is the non-obvious one and it matters: the server throttles a `STREAMING`
     * transcode to roughly real time, which would make a two-hour film take two hours to download.
     *
     * @param quality must be a transcoded step; [DownloadQuality.ORIGINAL] is served by [mediaUrl].
     * @param audioStreamIndex the absolute `MediaStream.index` of the single audio track to encode —
     *   the endpoint takes exactly one and the transcoder drops every other. `null` omits the
     *   parameter, which is what an item with no audio streams needs.
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
     * `audioStreamIndex` to `null` on the audio-only endpoint
     * (`EncodingHelper.AttachMediaSourceInfo`), so a *specific* extra track can only be asked for
     * through `/Videos`, which does honor it.
     *
     * @param streamIndex never the track a sibling [transcodedVideoUrl] call already baked in.
     */
    fun audioStreamUrl(
        itemId: UUID,
        mediaSourceId: String?,
        streamIndex: Int,
    ): String

    /**
     * One font attached to the source container, by its `MediaAttachment.index`. Fetched only for a
     * transcoded download: the server's re-encode carries video and audio and drops the attachments,
     * so this is the only route to the faces an ASS/SSA sidecar names. An `ORIGINAL` download keeps
     * the container and reads them out of it.
     */
    fun attachmentUrl(
        itemId: UUID,
        mediaSourceId: String,
        index: Int,
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

        override fun staticAudioUrl(
            itemId: UUID,
            mediaSourceId: String?,
        ): String =
            apiClient.audioApi.getAudioStreamUrl(
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
                // A fresh id per URL: it is what a later `stopEncodingProcess`, or any correlation
                // with the server's own transcode reporting, would need to name this encode.
                playSessionId = UUID.randomUUID().toString(),
                videoCodec = DownloadQuality.VIDEO_CODEC,
                audioCodec = DownloadQuality.AUDIO_CODEC,
                // Omitted, the server picks the source's default — the same track, but nothing would
                // have *recorded* which one, and offline there is no server left to ask.
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
                // WEBP at a capped width: a poster the offline UI draws at ~150 dp does not need the original.
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
                // Neither track may be copied: a copy could carry every other audio track with it, and
                // the video must actually shrink to the junk shape above.
                allowVideoStreamCopy = false,
                allowAudioStreamCopy = false,
                context = EncodingContext.STATIC,
            )

        override fun attachmentUrl(
            itemId: UUID,
            mediaSourceId: String,
            index: Int,
        ): String =
            apiClient.videoAttachmentsApi.getAttachmentUrl(
                videoId = itemId,
                mediaSourceId = mediaSourceId,
                index = index,
            )
    }
