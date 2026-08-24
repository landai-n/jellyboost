package dev.jellyboost.player.api

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every stream URL must carry `playSessionId` and `deviceId`: the server ties the active transcode to
 * them, and `stopEncodingProcess` cannot find the ffmpeg process to kill without them.
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

        /**
         * Carries the token as a query parameter because the sheet is fetched by Coil, which knows
         * nothing of this app's `Authorization` header.
         */
        override fun trickplayTileUrl(
            itemId: UUID,
            width: Int,
            tileIndex: Int,
            mediaSourceId: String?,
        ): String =
            withApiKey(
                apiClient.trickplayApi.getTrickplayTileImageUrl(
                    itemId = itemId,
                    width = width,
                    index = tileIndex,
                    mediaSourceId = mediaSourceId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                ),
            )

        /**
         * The guard matches `ApiKey` as a *parameter*, never as a substring: a path or item name
         * containing the letters must not be taken for an already-authorised URL (a 401 on the
         * television and nowhere else).
         */
        override fun withApiKey(url: String): String {
            if (API_KEY_PARAMETER.containsMatchIn(url)) return url
            val token = apiClient.accessToken
            if (token.isNullOrEmpty()) return url
            val separator = if (url.contains('?')) '&' else '?'
            return "$url$separator${ApiClient.QUERY_ACCESS_TOKEN}=$token"
        }

        private companion object {
            /** Jellyfin's query parameters are case-insensitive; the server spells it `ApiKey`. */
            val API_KEY_PARAMETER = Regex("[?&]${ApiClient.QUERY_ACCESS_TOKEN}=", RegexOption.IGNORE_CASE)
        }
    }
