package dev.jellyfinnative.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.data.cache.BrowseCacheWriter
import dev.jellyfinnative.data.mapper.ItemMapper
import dev.jellyfinnative.data.mapper.toBaseItemKind
import dev.jellyfinnative.data.mapper.toGetItemsRequest
import dev.jellyfinnative.data.paging.ItemPagingSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.filterApi
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
 * Every successful read is **written through** to Room with `source = BROWSE_CACHE` (M6,
 * docs/PLAN.md "Data layer"). That write is fire-and-forget — see [BrowseCacheWriter] — so this
 * class still behaves like a pure network reader from the caller's point of view.
 */
@Singleton
class OnlineJellyfinRepository
    @Inject
    constructor(
        private val apiClient: ApiClient,
        private val mapper: ItemMapper,
        private val browseCache: BrowseCacheWriter,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : JellyfinRepository {
        override suspend fun getUserViews(): AppResult<List<LibraryView>> =
            onIo {
                val response = apiClient.userViewsApi.getUserViews(includeHidden = false)
                browseCache.cacheViews(response.content.items)
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
                browseCache.cacheItems(response.content.items)
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
                browseCache.cacheItems(response.content.items)
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
                browseCache.cacheItems(response.content)
                mapper.toDomain(response.content)
            }

        // ---- M3 — library & search ----------------------------------------------------------

        override fun getItemsPaged(query: ItemQuery): Flow<PagingData<JellyfinItem>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        // Pinned to the page size so the very first load is one `limit=50` request
                        // like every other page, instead of Paging's default 3×.
                        initialLoadSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        prefetchDistance = PREFETCH_DISTANCE,
                        // The grid never draws placeholder cells, and turning them off is what
                        // lets the request skip the server-side total record count.
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemPagingSource(pageSize = ItemQuery.DEFAULT_PAGE_SIZE) { startIndex, limit ->
                        getItems(query.copy(startIndex = startIndex, limit = limit))
                    }
                },
            ).flow

        override suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        query.toGetItemsRequest(fields = CARD_FIELDS, imageTypes = CARD_IMAGE_TYPES),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        override suspend fun getFilterFacets(
            parentId: String?,
            itemTypes: List<ItemType>,
        ): AppResult<FilterFacets> =
            onIo {
                val response =
                    apiClient.filterApi.getQueryFiltersLegacy(
                        parentId = parentId?.let(UUID::fromString),
                        includeItemTypes = itemTypes.mapNotNull { it.toBaseItemKind() },
                    )
                FilterFacets(
                    genres = response.content.genres.orEmpty(),
                    // Newest first: the years a user filters by are almost always recent ones.
                    years =
                        response.content.years
                            .orEmpty()
                            .sortedDescending(),
                    officialRatings = response.content.officialRatings.orEmpty(),
                )
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
                // The one call that returns the *complete* DTO, so this is the write that makes a
                // cached item worth opening offline.
                browseCache.cacheItems(listOf(response.content))
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
                browseCache.cacheItems(response.content.items)
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
                browseCache.cacheItems(response.content.items)
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
                browseCache.cacheItems(response.content.items)
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
             * How close to the end of the loaded list the user has to scroll before the next page
             * is fetched. Deliberately far below Paging's default (which equals the page size and
             * would queue page 2 the moment page 1 renders): the M3 definition of done is one
             * request per screenful, not a read-ahead race.
             */
            const val PREFETCH_DISTANCE = 10

            /**
             * Episode rows draw a synopsis under the title, so the season list is the one list
             * request that pays for `OVERVIEW` (M4).
             */
            val EPISODE_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.OVERVIEW)
        }
    }
