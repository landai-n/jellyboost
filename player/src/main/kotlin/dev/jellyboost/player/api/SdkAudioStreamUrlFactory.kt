package dev.jellyboost.player.api

import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.model.SessionState
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioStreamUrlFactory] over the SDK's `UniversalAudioApi` URL builder (SDK 1.8.12).
 *
 * Two details are load-bearing and neither is obvious from the builder's signature:
 *
 * - **`playSessionId` is appended by hand.** `getUniversalAudioStreamUrl` has nineteen parameters
 *   and none of them is the play session; jellyfin-web appends `PlaySessionId=` to the built URL
 *   and the server binds it from the query string all the same. Without it the start/progress/stop
 *   reports name a session the stream is not tied to, and `stopEncodingProcess` cannot find the
 *   ffmpeg process to kill.
 * - **No `ApiKey`.** Unlike the Cast receiver's URLs, this stream is opened by *this* app's
 *   ExoPlayer, whose data source carries `JellyfinAuthInterceptor`'s `Authorization` header — the
 *   same arrangement as `directPlayUrl`, which likewise does not carry a token.
 */
@Singleton
internal class SdkAudioStreamUrlFactory
    @Inject
    constructor(
        private val apiClient: ApiClient,
        /**
         * Where the signed-in user's id comes from.
         *
         * `ApiClient` carries the token and the device but not the user, and the universal
         * endpoint's `userId` is what the server resolves the *user's* library and transcoding
         * policy against. It is optional — the server falls back to the authenticated user — but
         * jellyfin-web sends it, and a request that does not is one more difference to explain if
         * the dashboard ever disagrees with the browser.
         */
        private val sessionState: SessionStateHolder,
    ) : AudioStreamUrlFactory {
        override fun audioUniversalUrl(request: AudioStreamRequest): String {
            val url =
                apiClient.universalAudioApi.getUniversalAudioStreamUrl(
                    itemId = request.itemId,
                    container = request.containers,
                    mediaSourceId = request.mediaSourceId,
                    deviceId = apiClient.deviceInfo.id,
                    userId = (sessionState.state.value as? SessionState.LoggedIn)?.userId,
                    audioCodec = request.audioCodec,
                    // The transcode target is stereo: the plan's music path is a phone, a pair of
                    // headphones or a Bluetooth speaker, and asking for more only makes the encode
                    // bigger. A direct-played multichannel track is untouched by this.
                    maxAudioChannels = MAX_AUDIO_CHANNELS,
                    transcodingAudioChannels = MAX_AUDIO_CHANNELS,
                    maxStreamingBitrate = request.maxStreamingBitrate,
                    audioBitRate = request.audioBitRate,
                    transcodingContainer = request.transcodingContainer,
                    // HLS, not the device profile's mp3-over-HTTP audio transcoding profile: HLS is
                    // seekable, ExoPlayer plays it natively, and it sidesteps the video resolver's
                    // HLS-only transcode gate entirely (key decision 4).
                    transcodingProtocol = MediaStreamProtocol.HLS,
                    enableRedirection = true,
                )
            val separator = if (url.contains('?')) '&' else '?'
            return "$url$separator$PLAY_SESSION_PARAMETER=${request.playSessionId}"
        }

        private companion object {
            const val PLAY_SESSION_PARAMETER = "PlaySessionId"
            const val MAX_AUDIO_CHANNELS = 2
        }
    }
