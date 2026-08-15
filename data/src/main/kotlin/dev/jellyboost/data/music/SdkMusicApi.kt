package dev.jellyboost.data.music

import dev.jellyboost.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.LyricDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MusicApi] backed by jellyfin-sdk-kotlin.
 *
 * `userId` is left unset on the Instant Mix request, the same convention
 * `OnlineJellyfinRepository` uses everywhere: the SDK's [ApiClient] carries the access token and an
 * unset `userId` makes the server resolve the authenticated user itself.
 *
 * Every call hops onto [ioDispatcher] — the SDK's OkHttp backend reads response bodies on the
 * calling thread, and both of these can be reached from `viewModelScope` (main).
 */
@Singleton
internal class SdkMusicApi
    @Inject
    constructor(
        private val apiClient: ApiClient,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : MusicApi {
        override suspend fun getInstantMix(
            itemId: UUID,
            limit: Int,
        ): List<BaseItemDto> =
            withContext(ioDispatcher) {
                apiClient.instantMixApi
                    .getInstantMixFromItem(
                        itemId = itemId,
                        userId = null,
                        limit = limit,
                        fields = MIX_FIELDS,
                        enableImages = true,
                        enableUserData = true,
                        imageTypeLimit = 1,
                        enableImageTypes = MIX_IMAGE_TYPES,
                    ).content.items
            }

        override suspend fun getLyrics(itemId: UUID): LyricDto =
            withContext(ioDispatcher) {
                apiClient.lyricsApi.getLyrics(itemId).content
            }

        private companion object {
            /** Same card fields `OnlineJellyfinRepository`'s other music lists request. */
            val MIX_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
            val MIX_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
        }
    }
