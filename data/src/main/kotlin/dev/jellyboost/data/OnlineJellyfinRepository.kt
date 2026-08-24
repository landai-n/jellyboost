package dev.jellyboost.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.map
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.network.runCatchingApi
import dev.jellyboost.data.cache.BrowseCacheWriter
import dev.jellyboost.data.mapper.ItemMapper
import dev.jellyboost.data.mapper.toBaseItemKind
import dev.jellyboost.data.mapper.toDomain
import dev.jellyboost.data.mapper.toGetItemsRequest
import dev.jellyboost.data.music.MusicApi
import dev.jellyboost.data.paging.ItemPage
import dev.jellyboost.data.paging.ItemPagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leaving `userId` unset makes the server resolve the authenticated user, so this class needs no
 * session dependency. List calls stay lean (cards only); detail and playback calls fetch everything.
 * Every successful read is written through to Room as `BROWSE_CACHE`, fire-and-forget.
 */
@Singleton
@Suppress(
    "TooManyFunctions",
)
internal class OnlineJellyfinRepository
    @Inject
    constructor(
        private val apiClient: ApiClient,
        private val mapper: ItemMapper,
        private val browseCache: BrowseCacheWriter,
        private val musicApi: MusicApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : JellyfinRepository {
        override suspend fun getUserViews(): AppResult<List<LibraryView>> =
            onIo {
                val response = apiClient.userViewsApi.getUserViews(includeHidden = false)
                browseCache.cacheViews(response.content.items)
                val views = mapper.toLibraryViews(response.content.items)
                coroutineScope {
                    views
                        .map { view -> async { view.copy(itemCount = itemCountOrNull(view.id, view.collectionType)) } }
                        .awaitAll()
                }
            }

        /**
         * `getUserViews`' `ChildCount` is **not** this number — it counts the collection folder's
         * direct media folders, answering 3 for a 177-title library. Only this recursive query
         * matches what the grid pages over, so tile and grid header cannot disagree.
         *
         * `limit = 0` makes it a pure COUNT: `totalRecordCount` without serialising any item.
         *
         * A failure is deliberately not an error: a missing subtitle beats losing the home screen.
         */
        private suspend fun itemCountOrNull(
            libraryId: String,
            kind: CollectionKind,
        ): Int? =
            try {
                apiClient.itemsApi
                    .getItems(
                        GetItemsRequest(
                            parentId = UUID.fromString(libraryId),
                            includeItemTypes = kind.countItemTypes(),
                            recursive = true,
                            limit = 0,
                            enableTotalRecordCount = true,
                            // Nothing is drawn from this response, only counted.
                            enableImages = false,
                            enableUserData = false,
                        ),
                    ).content.totalRecordCount
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") throwable: Throwable,
            ) {
                Timber.w(throwable, "Library item count failed for %s; the tile drops its subtitle", libraryId)
                null
            }

        private fun CollectionKind.countItemTypes(): List<BaseItemKind> =
            if (this == CollectionKind.MUSIC) MUSIC_LIBRARY_COUNT_TYPES else LIBRARY_COUNT_TYPES

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
                            // jellyfin-web's Continue Watching lists video only; audio has its own row.
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
                            // jellyfin-web parity: in-progress episodes belong to Continue Watching,
                            // and a series untouched past the "Days in Next Up" window drops out.
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

        // ---- library & search -----------------------------------------------------------------

        override fun getItemsPaged(
            query: ItemQuery,
            onTotalCount: (Int) -> Unit,
        ): Flow<PagingData<JellyfinItem>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        // Pinned to the page size; Paging would otherwise make the first load 3x.
                        initialLoadSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        prefetchDistance = PREFETCH_DISTANCE,
                        // No placeholder cells, which lets every page but the first skip the
                        // server-side total record count.
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemPagingSource(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        onTotalCount = onTotalCount,
                    ) { startIndex, limit, withTotalCount ->
                        getItemsPage(
                            query.copy(
                                startIndex = startIndex,
                                limit = limit,
                                includeTotalCount = withTotalCount,
                            ),
                        )
                    }
                },
            ).flow

        override suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>> =
            getItemsPage(query).map { it.items }

        /**
         * `totalRecordCount` is non-null on the wire and reads 0 when the request left the count off,
         * so the flag — not the value — decides whether there is a number to report.
         */
        private suspend fun getItemsPage(query: ItemQuery): AppResult<ItemPage> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        query.toGetItemsRequest(fields = CARD_FIELDS, imageTypes = CARD_IMAGE_TYPES),
                    )
                browseCache.cacheItems(response.content.items)
                ItemPage(
                    items = mapper.toDomain(response.content.items),
                    totalCount = if (query.includeTotalCount) response.content.totalRecordCount else null,
                )
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
                    years =
                        response.content.years
                            .orEmpty()
                            .sortedDescending(),
                    officialRatings = response.content.officialRatings.orEmpty(),
                )
            }

        // ---- item detail -----------------------------------------------------------------------

        /**
         * There is no `fields` argument to pass: `/Users/{userId}/Items/{itemId}` is the one endpoint
         * that always serialises the **complete** field set.
         */
        override suspend fun getItem(id: String): AppResult<JellyfinItem> =
            onIo {
                val response = apiClient.userLibraryApi.getItem(itemId = UUID.fromString(id))
                // The only complete DTO, so the only write allowed to replace a downloaded item's
                // stored blob. Every other write here is lean and must preserve it.
                browseCache.cacheItems(listOf(response.content), full = true)
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
                            // Missing seasons ("Specials" placeholders with nothing on disk) would
                            // render as dead cards; jellyfin-web hides them too.
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

        /**
         * `/Shows/{id}/Episodes` with no season filter returns the whole run, already in broadcast
         * order.
         */
        override suspend fun getSeriesEpisodes(seriesId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.tvShowsApi.getEpisodes(
                        GetEpisodesRequest(
                            seriesId = UUID.fromString(seriesId),
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

        // ---- end item detail ------------------------------------------------------------------

        // ---- music -----------------------------------------------------------------------------

        override suspend fun getAlbumTracks(albumId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        GetItemsRequest(
                            parentId = UUID.fromString(albumId),
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            recursive = true,
                            sortBy =
                                listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
                            sortOrder = listOf(SortOrder.ASCENDING),
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                        ),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        override suspend fun getArtistAlbums(artistId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        GetItemsRequest(
                            albumArtistIds = listOf(UUID.fromString(artistId)),
                            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                            recursive = true,
                            sortBy =
                                listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                            // Index-matched to sortBy, entry for entry.
                            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING),
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                        ),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        override suspend fun getArtistTopTracks(
            artistId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        GetItemsRequest(
                            artistIds = listOf(UUID.fromString(artistId)),
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            recursive = true,
                            sortBy = listOf(ItemSortBy.PLAY_COUNT),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            limit = limit,
                            fields = CARD_FIELDS,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                        ),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        /**
         * `/Playlists/{id}/Items`, not a `parentId` query: only the dedicated endpoint preserves
         * playlist order. Filtered to audio because a Jellyfin playlist may legally mix in episodes
         * and films, and the music queue's resolver would build `/Audio/{id}/universal` URLs for
         * them (`SdkDownloadApi.getPlaylistTrackIds` drops them the same way).
         */
        override suspend fun getPlaylistItems(playlistId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.playlistsApi.getPlaylistItems(
                        GetPlaylistItemsRequest(
                            playlistId = UUID.fromString(playlistId),
                            fields = CARD_FIELDS,
                            enableImages = true,
                            enableImageTypes = CARD_IMAGE_TYPES,
                            imageTypeLimit = 1,
                            enableUserData = true,
                        ),
                    )
                val tracks = response.content.items.filter { it.type == BaseItemKind.AUDIO }
                browseCache.cacheItems(tracks)
                mapper.toDomain(tracks)
            }

        // ---- end music ------------------------------------------------------------------------

        // ---- Continue Listening ---------------------------------------------------------------

        override suspend fun getResumeAudioItems(limit: Int): AppResult<List<JellyfinItem>> =
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
                            mediaTypes = listOf(MediaType.AUDIO),
                        ),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        // ---- end Continue Listening -------------------------------------------------------------

        // ---- Instant Mix & lyrics ---------------------------------------------------------------

        override suspend fun getInstantMix(
            itemId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val dtos = musicApi.getInstantMix(UUID.fromString(itemId), limit)
                browseCache.cacheItems(dtos)
                mapper.toDomain(dtos)
            }

        override suspend fun getLyrics(itemId: String): AppResult<Lyrics> =
            onIo {
                musicApi.getLyrics(UUID.fromString(itemId)).toDomain()
            }

        // ---- end Instant Mix & lyrics ------------------------------------------------------------

        /**
         * The SDK's OkHttp backend reads response bodies on the *calling* thread, so a call launched
         * from `viewModelScope` dies with `NetworkOnMainThreadException` without this hop.
         */
        private suspend fun <T> onIo(block: suspend () -> T): AppResult<T> =
            withContext(ioDispatcher) { runCatchingApi { block() } }

        private companion object {
            /** The one field beyond the defaults a card needs: poster vs thumbnail. */
            val CARD_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)

            val CARD_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)

            /**
             * Projected from [ItemType.LIBRARY_TILE_TYPES] rather than hand-written, so the grid a
             * tile opens cannot report a different total than the tile did. Music libraries get
             * their own list instead of extending this one — see [countItemTypes].
             */
            val LIBRARY_COUNT_TYPES = ItemType.LIBRARY_TILE_TYPES.mapNotNull { it.toBaseItemKind() }

            /** Albums, the top-level browsable unit a music library shows. */
            val MUSIC_LIBRARY_COUNT_TYPES = listOf(BaseItemKind.MUSIC_ALBUM)

            /** jellyfin-web's default "Days in Next Up" user setting. */
            const val NEXT_UP_WINDOW_DAYS = 365L

            /**
             * Far below Paging's default, which equals the page size and would queue page 2 the
             * moment page 1 renders: one request per screenful, not a read-ahead race.
             */
            const val PREFETCH_DISTANCE = 10

            /** Episode rows draw a synopsis, so this is the one list request that pays for `OVERVIEW`. */
            val EPISODE_FIELDS = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.OVERVIEW)
        }
    }
