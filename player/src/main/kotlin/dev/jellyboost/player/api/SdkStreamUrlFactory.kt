package dev.jellyboost.player.api

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
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

        /**
         * The tile URL, with the access token appended as a query parameter.
         *
         * Trickplay is an authorised endpoint, and the sheet is fetched by Coil — an image loader
         * with no knowledge of this app's `Authorization` header. Jellyfin accepts the token as the
         * `ApiKey` query parameter for exactly this case, which keeps the seam a plain `String` the
         * scrubber can hand to any loader. The Cast receiver needs the identical treatment, which is
         * why the appending itself now lives in [withApiKey].
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
         * Appends `ApiKey`, unless the URL already has one.
         *
         * The guard matches the parameter as a *parameter* rather than as a substring: a URL that
         * merely contains the letters — a subtitle path, an item name — must not be mistaken for one
         * that is already authorised, because the mistake surfaces as a 401 on the television and
         * nowhere else.
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
