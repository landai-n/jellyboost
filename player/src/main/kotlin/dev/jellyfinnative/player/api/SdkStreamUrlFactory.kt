package dev.jellyfinnative.player.api

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StreamUrlFactory] backed by the SDK's URL builders.
 *
 * The `playSessionId` and `deviceId` query parameters are not decoration: the server ties an
 * active transcode to them, and `stopEncodingProcess` will not find the ffmpeg process to kill if
 * the stream was requested without them.
 */
@Singleton
internal class SdkStreamUrlFactory
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : StreamUrlFactory {
        override fun directPlayUrl(
            itemId: UUID,
            mediaSourceId: String,
            playSessionId: String,
        ): String =
            apiClient.videosApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
            )

        override fun directStreamUrl(
            itemId: UUID,
            container: String,
            mediaSourceId: String,
            playSessionId: String,
        ): String =
            apiClient.videosApi.getVideoStreamByContainerUrl(
                itemId = itemId,
                container = container,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                deviceId = apiClient.deviceInfo.id,
            )

        override fun absoluteUrl(path: String): String = apiClient.createUrl(path)
    }
