package dev.jellyfinnative.data

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.data.mapper.ItemMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JellyfinRepository] backed by live server calls through jellyfin-sdk-kotlin.
 *
 * The user is implicit: the SDK's [ApiClient] carries the access token and leaving `userId` unset
 * makes the server resolve the authenticated user, so this class needs no session dependency of
 * its own.
 *
 * Requests stay deliberately lean — only the fields a card actually draws — following the pattern
 * the plan takes from Swiftfin: list calls are minimal, detail and playback calls fetch everything
 * (docs/PLAN.md, "Screens" → ItemDetail).
 *
 * **Not implemented here, by design:** write-through caching into Room with
 * `source = BROWSE_CACHE`. The Room browse cache and the offline/delegating repositories arrive in
 * M6 (docs/PLAN.md, "Data layer"); until then this class is purely a network reader.
 */
@Singleton
class OnlineJellyfinRepository
    @Inject
    constructor(
        private val apiClient: ApiClient,
        private val mapper: ItemMapper,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : JellyfinRepository {
        override suspend fun getUserViews(): AppResult<List<LibraryView>> =
            onIo {
                val response = apiClient.userViewsApi.getUserViews(includeHidden = false)
                // The mapper drops everything outside v1 scope (music, live TV, photos, …).
                mapper.toLibraryViews(response.content.items)
            }

        override suspend fun getResumeItems(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getResumeItems(
                        GetResumeItemsRequest(
                            limit = limit,
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                            enableTotalRecordCount = false,
                        ),
                    )
                mapper.toDomain(response.content.items)
            }

        override suspend fun getNextUp(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.tvShowsApi.getNextUp(
                        GetNextUpRequest(
                            limit = limit,
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                            enableTotalRecordCount = false,
                        ),
                    )
                mapper.toDomain(response.content.items)
            }

        override suspend fun getLatestMedia(
            parentId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.userLibraryApi.getLatestMedia(
                        GetLatestMediaRequest(
                            parentId = UUID.fromString(parentId),
                            limit = limit,
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                        ),
                    )
                mapper.toDomain(response.content)
            }

        /**
         * Runs an SDK call off the caller's dispatcher.
         *
         * The SDK's OkHttp backend reads response bodies on the calling thread, so a call
         * launched from `viewModelScope` (main) dies with `NetworkOnMainThreadException`
         * unless it is hopped onto [ioDispatcher] first.
         */
        private suspend fun <T> onIo(block: suspend () -> T): AppResult<T> =
            withContext(ioDispatcher) { runCatchingApi { block() } }

        private companion object {
            /**
             * The only field a card needs beyond the defaults: it tells the UI whether the server's
             * primary image is a poster or a thumbnail.
             */
            val CARD_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)

            /** Artwork the cards can actually draw — anything else is wasted server work. */
            val CARD_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
        }
    }
