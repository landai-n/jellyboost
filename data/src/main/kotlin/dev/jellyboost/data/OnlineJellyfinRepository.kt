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
@Suppress(
    // One member per [JellyfinRepository] method, by construction — the interface is the
    // implementation's whole surface, and M13 Phase 2's four music members (docs/notes/
    // music-m13-plan.md) pushed this class from 17 to 21; Phase 4's `getResumeAudioItems` to 22;
    // Phase 6's `getInstantMix`/`getLyrics` to 24. Splitting it would mean two
    // repositories implementing one interface, which is the parallel-model the plan's
    // "extend, don't parallel" rule (decision 5) rules out for the domain layer and would be
    // worse here, for the same reason. Logged in DECISIONS.md.
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
                // The mapper drops everything outside app scope (live TV, photos, … — music
                // joined `CollectionKind.SUPPORTED` in M13 Phase 2).
                val views = mapper.toLibraryViews(response.content.items)
                coroutineScope {
                    views
                        .map { view -> async { view.copy(itemCount = itemCountOrNull(view.id, view.collectionType)) } }
                        .awaitAll()
                }
            }

        /**
         * How many titles the library with [libraryId] actually holds, or `null` if the server could
         * not say.
         *
         * `getUserViews` carries a `ChildCount` per library and it is **not** this number: it counts
         * the collection folder's direct children — its media folders — so the dev server answers 3
         * for a 177-movie library and 6 for a 20-series one. Only a recursive query over the
         * library's titles gives the count the tile promises, and it is the same query the library
         * grid pages over (`LibraryUiState.GRID_ITEM_TYPES`, `recursive = true`), so the tile's
         * number and the grid header's "N items" cannot disagree.
         *
         * `limit = 0` makes it a pure COUNT: the server still reports `totalRecordCount` but
         * serialises no items at all. One such request per library, all in flight together
         * ([getUserViews] runs them under one `coroutineScope`), so the home load pays one extra
         * round trip rather than one per library.
         *
         * A failure here is deliberately *not* an error for the caller: the subtitle is one line on
         * a tile, and losing the whole libraries row — and with it the home screen — over a count
         * would be a far worse trade than a tile that draws its name alone. Cancellation is the one
         * exception, re-thrown so a cancelled home load does not linger.
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

        /** Which [BaseItemKind]s [itemCountOrNull] asks for, by the library's own kind. */
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

        override fun getItemsPaged(
            query: ItemQuery,
            onTotalCount: (Int) -> Unit,
        ): Flow<PagingData<JellyfinItem>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        // Pinned to the page size so the very first load is one `limit=50` request
                        // like every other page, instead of Paging's default 3×.
                        initialLoadSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        prefetchDistance = PREFETCH_DISTANCE,
                        // The grid never draws placeholder cells, which is what lets every page
                        // but the first skip the server-side total record count.
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
         * The one `getItems` round trip, with the server's total attached when [query] asked for it.
         *
         * `totalRecordCount` is a non-null `Int` on the wire and reads 0 when the request left the
         * count off, so the flag — not the value — decides whether there is a number to report.
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
                // cached item worth opening offline — and the only one allowed to replace the stored
                // blob of a downloaded item, which is what `full = true` says. Every other write in
                // this class is a lean list response and must preserve it (see `BrowseCacheWriter`).
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

        /**
         * The same endpoint as [getEpisodes] with the season filter left off — `/Shows/{id}/Episodes`
         * is rooted at the series and returns the whole run, already in broadcast order, when no
         * season narrows it.
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

        // ---- end M4 -------------------------------------------------------------------------

        // ---- M13 Phase 2 — music --------------------------------------------------------------

        override suspend fun getAlbumTracks(albumId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val response =
                    apiClient.itemsApi.getItems(
                        GetItemsRequest(
                            parentId = UUID.fromString(albumId),
                            includeItemTypes = listOf(BaseItemKind.AUDIO),
                            recursive = true,
                            // Disc, then track, then a stable alphabetical tiebreak.
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
                            // Index-matched to sortBy: newest year, newest exact date, A→Z tiebreak.
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
         * `/Playlists/{id}/Items` rather than a `parentId` items query: the plan's Phase 2 note
         * flags that a generic items query is not guaranteed to preserve playlist order, while the
         * dedicated endpoint is built exactly for that (docs/notes/music-m13-plan.md, Phase 2).
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
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        // ---- end M13 Phase 2 ------------------------------------------------------------------

        // ---- M13 Phase 4 — Continue Listening ---------------------------------------------------

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
                            // [getResumeItems]'s own mirror image: audio rather than video.
                            mediaTypes = listOf(MediaType.AUDIO),
                        ),
                    )
                browseCache.cacheItems(response.content.items)
                mapper.toDomain(response.content.items)
            }

        // ---- end M13 Phase 4 ------------------------------------------------------------------

        // ---- M13 Phase 6 — Instant Mix & lyrics ------------------------------------------------

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

        // ---- end M13 Phase 6 ------------------------------------------------------------------

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

            /**
             * The item kinds a movie or TV library tile's count counts.
             *
             * Projected from [ItemType.LIBRARY_TILE_TYPES] (DUP-11) via the same [toBaseItemKind]
             * `:data` already maps query types through, rather than a second hand-written
             * `BaseItemKind` pair: the grid a tile opens must not report a different total than the
             * tile did, and `:data` cannot depend on `:feature:library` to share its list directly.
             *
             * **Unchanged by M13 Phase 2** — pinned by `OnlineJellyfinRepositoryTest`'s "counts each
             * library's titles instead of trusting ChildCount", which asserts a movie *and* a TV
             * library's count request both send exactly `[MOVIE, SERIES]`. Music libraries get their
             * own list instead of extending this one — see [countItemTypes].
             */
            val LIBRARY_COUNT_TYPES = ItemType.LIBRARY_TILE_TYPES.mapNotNull { it.toBaseItemKind() }

            /**
             * The item kind a music library tile's count counts: its albums, the top-level
             * browsable unit a music library shows — same role [LIBRARY_COUNT_TYPES] plays for a
             * movie or TV library (M13 Phase 2, docs/notes/music-m13-plan.md item 2).
             */
            val MUSIC_LIBRARY_COUNT_TYPES = listOf(BaseItemKind.MUSIC_ALBUM)

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
