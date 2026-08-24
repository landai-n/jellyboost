package dev.jellyboost.player.api

import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.model.SessionState
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `playSessionId` is appended by hand: `getUniversalAudioStreamUrl` has no parameter for it, yet the
 * server binds it from the query string, and without it `stopEncodingProcess` cannot find the ffmpeg
 * process to kill.
 *
 * No `ApiKey` in the URL — ExoPlayer's data source carries `JellyfinAuthInterceptor`'s header.
 */
@Singleton
internal class SdkAudioStreamUrlFactory
    @Inject
    constructor(
        private val apiClient: ApiClient,
        /** `ApiClient` carries the token but not the user id the endpoint resolves policy against. */
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
                    // Transcode target is stereo; a direct-played multichannel track is untouched.
                    maxAudioChannels = MAX_AUDIO_CHANNELS,
                    transcodingAudioChannels = MAX_AUDIO_CHANNELS,
                    maxStreamingBitrate = request.maxStreamingBitrate,
                    audioBitRate = request.audioBitRate,
                    transcodingContainer = request.transcodingContainer,
                    // HLS, not the profile's mp3-over-HTTP transcoding profile: HLS stays seekable.
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
