package dev.jellyboost.player.api

import dev.jellyboost.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlayerApi] backed by jellyfin-sdk-kotlin.
 *
 * Every call hops onto the IO dispatcher: the SDK's operations are `suspend` but block on OkHttp
 * underneath, and these run from the player's main-thread callbacks.
 */
@Singleton
internal class SdkPlayerApi
    @Inject
    constructor(
        private val apiClient: ApiClient,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : PlayerApi {
        override val deviceId: String? get() = apiClient.deviceInfo.id

        override suspend fun getPlaybackInfo(
            itemId: UUID,
            request: PlaybackInfoDto,
        ): PlaybackInfoResponse =
            withContext(ioDispatcher) {
                apiClient.mediaInfoApi.getPostedPlaybackInfo(itemId = itemId, data = request).content
            }

        override suspend fun reportPlaybackStart(info: PlaybackStartInfo) {
            withContext(ioDispatcher) { apiClient.playStateApi.reportPlaybackStart(info) }
        }

        override suspend fun reportPlaybackProgress(info: PlaybackProgressInfo) {
            withContext(ioDispatcher) { apiClient.playStateApi.reportPlaybackProgress(info) }
        }

        override suspend fun reportPlaybackStopped(info: PlaybackStopInfo) {
            withContext(ioDispatcher) { apiClient.playStateApi.reportPlaybackStopped(info) }
        }

        override suspend fun stopEncodingProcess(
            deviceId: String,
            playSessionId: String,
        ) {
            withContext(ioDispatcher) {
                apiClient.hlsSegmentApi.stopEncodingProcess(
                    deviceId = deviceId,
                    playSessionId = playSessionId,
                )
            }
        }

        override suspend fun getTrickplayInfo(itemId: UUID): Map<String, Map<String, TrickplayInfoDto>> =
            withContext(ioDispatcher) {
                apiClient.userLibraryApi
                    .getItem(itemId = itemId)
                    .content.trickplay
                    .orEmpty()
            }

        override suspend fun getMediaSegments(
            itemId: UUID,
            types: Collection<MediaSegmentType>,
        ): List<MediaSegmentDto> =
            withContext(ioDispatcher) {
                apiClient.mediaSegmentsApi
                    .getItemSegments(itemId = itemId, includeSegmentTypes = types)
                    .content.items
            }

        override suspend fun getBitrateTestBytes(size: Int): ByteArray =
            withContext(ioDispatcher) {
                apiClient.mediaInfoApi.getBitrateTestBytes(size).content
            }
    }
