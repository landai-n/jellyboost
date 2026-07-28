package dev.jellyfinnative.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.database.entities.UserDataEntity
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.paging.ItemPagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JellyfinRepository] served entirely from Room — no network, ever.
 *
 * ### What it shows
 * The plan's offline browse scope is **downloaded items only**, with one deliberate exception:
 * "cached parents of downloaded items still open, e.g. series page of a downloaded episode"
 * (docs/PLAN.md, "Confirmed decisions"). So every *list* here filters on
 * [ItemSource.DOWNLOAD] while [getItem] serves any cached row.
 *
 * The home rows are reconstructed from local state rather than fetched:
 *
 * | row | offline definition |
 * |---|---|
 * | Continue watching | downloads this device has a resume position for |
 * | Next up | the first unwatched downloaded episode of each series |
 * | Latest *library* | the most recently downloaded items of that library |
 *
 * ### What it never does
 * It never throws for a missing item and never reports one as an error: [getItem] answers with a
 * placeholder carrying `available = false`, which is the flag `JellyfinItem` already defines for
 * "known, but not openable right now". A repository that failed here would turn every stale
 * deep-link into a crash-shaped error screen.
 *
 * Until the M7 download pipeline exists nothing ever writes a `DOWNLOAD` row, so in practice these
 * lists are empty on a real device — the behaviour is pinned by unit tests that seed Room instead.
 */
@Singleton
class OfflineJellyfinRepository
    @Inject
    constructor(
        private val itemDao: ItemDao,
        private val libraryViewDao: LibraryViewDao,
        private val userDataDao: UserDataDao,
        private val mapper: ItemEntityMapper,
        private val sessionRepository: SessionRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : JellyfinRepository {
        override suspend fun getUserViews(): AppResult<List<LibraryView>> =
            onIo {
                libraryViewDao.getAll().map(mapper::toDomain)
            }

        override suspend fun getResumeItems(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val userId = currentUserId() ?: return@onIo emptyList()
                itemDao
                    .resumeDownloaded(ItemSource.DOWNLOAD, userId, limit)
                    .withLocalUserData(userId)
            }

        override suspend fun getNextUp(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val userId = currentUserId() ?: return@onIo emptyList()
                itemDao
                    .unwatchedDownloadedEpisodes(
                        source = ItemSource.DOWNLOAD,
                        userId = userId,
                        episodeType = ItemType.EPISODE,
                        seriesId = null,
                    )
                    // The query is already ordered by series then season then episode, so the first
                    // row of each series *is* its next episode.
                    .distinctBy { it.seriesId }
                    .take(limit)
                    .withLocalUserData(userId)
            }

        override suspend fun getLatestMedia(
            parentId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val library = parentId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .latestDownloaded(ItemSource.DOWNLOAD, typesOf(library, LIST_ITEM_TYPES), limit)
                    .withLocalUserData(currentUserId())
            }

        // ---- library grid & search ---------------------------------------------------------

        override fun getItemsPaged(query: ItemQuery): Flow<PagingData<JellyfinItem>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        initialLoadSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        enablePlaceholders = false,
                    ),
                // Deliberately the same `ItemPagingSource` the online grid uses rather than a
                // Room-generated `PagingSource`: the offset/limit contract is identical, and one
                // paging implementation means one set of edge cases (see DECISIONS.md).
                pagingSourceFactory = {
                    ItemPagingSource(pageSize = ItemQuery.DEFAULT_PAGE_SIZE) { startIndex, limit ->
                        getItems(query.copy(startIndex = startIndex, limit = limit))
                    }
                },
            ).flow

        override suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>> =
            onIo {
                val requested = query.itemTypes.ifEmpty { LIST_ITEM_TYPES }
                val term = query.searchTerm?.trim()
                val rows =
                    if (!term.isNullOrEmpty()) {
                        // Search is deliberately library-wide: offline there are a handful of items
                        // and finding one you downloaded matters more than which grid you were in.
                        itemDao.searchDownloaded(ItemSource.DOWNLOAD, requested, term, query.limit)
                    } else {
                        itemDao.pagingDownloaded(
                            source = ItemSource.DOWNLOAD,
                            types = typesOf(query.parentId?.toUuidOrNull(), requested),
                            descending = query.sortOrder == SortOrder.DESCENDING,
                            limit = query.limit,
                            offset = query.startIndex,
                        )
                    }
                rows.withLocalUserData(currentUserId())
            }

        override suspend fun getFilterFacets(
            parentId: String?,
            itemTypes: List<ItemType>,
        ): AppResult<FilterFacets> =
            onIo {
                val rows =
                    itemDao.allBySource(ItemSource.DOWNLOAD, itemTypes.ifEmpty { LIST_ITEM_TYPES })
                FilterFacets(
                    genres = rows.flatMap { it.genres }.distinct().sorted(),
                    years = rows.mapNotNull { it.productionYear }.distinct().sortedDescending(),
                    officialRatings = rows.mapNotNull { it.officialRating }.distinct().sorted(),
                )
            }

        // ---- item detail -------------------------------------------------------------------

        override suspend fun getItem(id: String): AppResult<JellyfinItem> =
            onIo {
                val uuid = id.toUuidOrNull() ?: return@onIo unavailable(id)
                val entity = itemDao.getItem(uuid) ?: return@onIo unavailable(id)
                val userData = currentUserId()?.let { userDataDao.getUserData(uuid, it) }
                mapper.toDomainOrNull(entity, userData) ?: unavailable(id)
            }

        override suspend fun getSeasons(seriesId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val series = seriesId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .seasonsOfSeries(ItemSource.DOWNLOAD, series, ItemType.SEASON)
                    .withLocalUserData(currentUserId())
            }

        override suspend fun getEpisodes(
            seriesId: String,
            seasonId: String,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val season = seasonId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .episodesOfSeason(ItemSource.DOWNLOAD, season, ItemType.EPISODE)
                    .withLocalUserData(currentUserId())
            }

        override suspend fun getNextUpForSeries(seriesId: String): AppResult<JellyfinItem?> =
            onIo {
                val userId = currentUserId() ?: return@onIo null
                val series = seriesId.toUuidOrNull() ?: return@onIo null
                itemDao
                    .unwatchedDownloadedEpisodes(
                        source = ItemSource.DOWNLOAD,
                        userId = userId,
                        episodeType = ItemType.EPISODE,
                        seriesId = series,
                    ).firstOrNull()
                    ?.let { mapper.toDomainOrNull(it, userDataDao.getUserData(it.id, userId)) }
            }

        /**
         * Always empty offline.
         *
         * "More like this" is a server-side recommendation over the *whole* library; the two or
         * three downloaded items that happen to share a genre are not that, and presenting them as
         * such would be a worse answer than an absent row.
         */
        override suspend fun getSimilarItems(
            id: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> = AppResult.Success(emptyList())

        // ---- helpers -------------------------------------------------------------------------

        /**
         * Overlays this device's local playback state onto cached items.
         *
         * Offline, `user_data` outranks whatever the blob was cached with: a position written while
         * the network was down exists nowhere else yet.
         */
        private suspend fun List<ItemEntity>.withLocalUserData(userId: UUID?): List<JellyfinItem> {
            if (isEmpty()) return emptyList()
            val userData: Map<UUID, UserDataEntity> =
                userId
                    ?.let { userDataDao.getUserDataFor(map { row -> row.id }, it) }
                    ?.associateBy { it.itemId }
                    .orEmpty()
            return mapper.toDomain(this, userData)
        }

        /**
         * Which item kinds a library's offline list may contain.
         *
         * This is how an offline row is attributed to a library, and it replaces the parent-id
         * linkage the queries used to filter on. A downloaded row's `parentId` is its containing
         * *folder* — when the server sends one at all; the M7 device walk found downloaded films
         * stored with `parentId NULL` — and a folder is not the library-view id the grid asks
         * about, so the predicate could only ever be empty (DECISIONS.md 2026-07-28).
         *
         * Type is exact for the libraries v1 supports: a downloaded film belongs to the movie
         * library, a downloaded series or episode to the TV one. It is *not* a general rule (two
         * movie libraries would share their downloads), which is why it is a documented v1
         * simplification rather than a new invariant.
         *
         * An unknown library id — one not in the cached `library_views` — narrows nothing, so a
         * grid still lists what was asked for rather than nothing at all.
         */
        private suspend fun typesOf(
            libraryId: UUID?,
            requested: List<ItemType>,
        ): List<ItemType> {
            val id = libraryId ?: return requested
            val kind =
                libraryViewDao
                    .getAll()
                    .firstOrNull { it.id == id }
                    ?.let { view -> CollectionKind.entries.firstOrNull { it.name == view.collectionType } }

            val allowed =
                when (kind) {
                    CollectionKind.MOVIES -> MOVIE_LIBRARY_TYPES
                    CollectionKind.TVSHOWS -> TV_LIBRARY_TYPES
                    else -> return requested
                }
            return requested.filter { it in allowed }.ifEmpty { allowed }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId

        /**
         * Runs a Room read, folding any storage failure into [AppError.Storage].
         *
         * Cancellation is re-thrown for the same reason it is on the network path: a cancelled
         * ViewModel scope must not render an error state.
         */
        private suspend fun <T> onIo(block: suspend () -> T): AppResult<T> =
            withContext(ioDispatcher) {
                try {
                    AppResult.Success(block())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Timber.e(error, "Offline read failed")
                    AppResult.Failure(AppError.Storage(error))
                }
            }

        private companion object {
            /** The item kinds any offline list can contain; seasons and folders are navigated to. */
            val LIST_ITEM_TYPES = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)

            /** What a movie library's offline lists may show. */
            val MOVIE_LIBRARY_TYPES = listOf(ItemType.MOVIE)

            /** What a TV library's offline lists may show — the grid asks for series, Latest also episodes. */
            val TV_LIBRARY_TYPES = listOf(ItemType.SERIES, ItemType.EPISODE)
        }
    }

/**
 * The answer for an item we do not have.
 *
 * `available = false` is `JellyfinItem`'s own vocabulary for "known of, but not openable"; the
 * plan requires it here instead of an error (docs/PLAN.md, "Data layer" →
 * `OfflineJellyfinRepository`).
 */
private fun unavailable(id: String): JellyfinItem =
    JellyfinItem(id = id, name = "", type = ItemType.UNKNOWN, available = false)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
