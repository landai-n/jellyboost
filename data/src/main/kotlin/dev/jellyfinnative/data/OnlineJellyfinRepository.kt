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
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import java.time.LocalDateTime
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
                            // jellyfin-web's "Continue Watching" only lists video; without this an
                            // in-progress audiobook/track would appear here but not on the web home.
                            mediaTypes = listOf(MediaType.VIDEO),
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
                            // Mirror jellyfin-web's home: episodes with playback progress live in
                            // Continue Watching only, and series untouched for over a year drop out
                            // (web's "Days in Next Up" user setting, default 365).
                            enableResumable = false,
                            nextUpDateCutoff = LocalDateTime.now().minusDays(NEXT_UP_WINDOW_DAYS),
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

        // ---- M4 — item detail ---------------------------------------------------------------

        /**
         * Note there is no `fields` argument to pass here: `/Users/{userId}/Items/{itemId}` is the
         * one endpoint that always serialises the **complete** field set (media sources, streams,
         * chapters, trickplay, people, taglines, genres, overview). That is exactly the "detail is
         * full" half of the Swiftfin pattern, and it is the same call jellyfin-web makes when you
         * open an item.
         */
        override suspend fun getItem(id: String): AppResult<JellyfinItem> =
            onIo {
                val response = apiClient.userLibraryApi.getItem(itemId = UUID.fromString(id))
                mapper.toDomain(response.content)
            }

        override suspend fun getSeasons(seriesId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.tvShowsApi.getSeasons(
                        GetSeasonsRequest(
                            seriesId = UUID.fromString(seriesId),
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                            // jellyfin-web hides "Specials" placeholders that have no episodes on
                            // disk; missing seasons would render as dead cards.
                            isMissing = false,
                        ),
                    )
                mapper.toDomain(response.content.items)
            }

        override suspend fun getEpisodes(
            seriesId: String,
            seasonId: String,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.tvShowsApi.getEpisodes(
                        GetEpisodesRequest(
                            seriesId = UUID.fromString(seriesId),
                            seasonId = UUID.fromString(seasonId),
                            // The episode list is the one place a synopsis is worth the payload:
                            // it is drawn directly under each row.
                            fields = EPISODE_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                            isMissing = false,
                        ),
                    )
                mapper.toDomain(response.content.items)
            }

        override suspend fun getNextUpForSeries(seriesId: String): AppResult<JellyfinItem?> =
            onIo {
                val response =
                    apiClient.tvShowsApi.getNextUp(
                        GetNextUpRequest(
                            seriesId = UUID.fromString(seriesId),
                            limit = 1,
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                            enableTotalRecordCount = false,
                        ),
                    )
                response.content.items
                    .firstOrNull()
                    ?.let(mapper::toDomain)
            }

        override suspend fun getSimilarItems(
            id: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.libraryApi.getSimilarItems(
                        GetSimilarItemsRequest(
                            itemId = UUID.fromString(id),
                            limit = limit,
                            fields = CARD_FIELDS,
                        ),
                    )
                mapper.toDomain(response.content.items)
            }

        // ---- end M4 -------------------------------------------------------------------------

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

            /** jellyfin-web's default "Days in Next Up" user setting. */
            const val NEXT_UP_WINDOW_DAYS = 365L

            /**
             * Episode rows draw a synopsis under the title, so the season list is the one list
             * request that pays for `OVERVIEW` (M4).
             */
            val EPISODE_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.OVERVIEW)
        }
    }
