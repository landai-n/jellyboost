package dev.jellyboost.data

import android.database.sqlite.SQLiteException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.map
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.FilterFacets
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.music.Lyrics
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.DownloadedItemKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LatestDownloadKey
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.paging.ItemPage
import dev.jellyboost.data.paging.ItemPagingSource
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
 * Scope is [ItemSource.DOWNLOAD] for every *list*; [getItem] serves any cached row, because cached
 * parents of downloaded items must still open. A missing item is never an error — [getItem] answers
 * with an `available = false` placeholder, so a stale deep-link is not a crash-shaped error screen.
 */
@Singleton
@Suppress(
    "TooManyFunctions",
)
internal class OfflineJellyfinRepository
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
                    .asHomeCards()
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
                    // Ordered series/season/episode, so each series' first row *is* its next episode.
                    .distinctBy { it.seriesId }
                    .take(limit)
                    .withLocalUserData(userId)
                    .asHomeCards()
            }

        /**
         * One card per series, matching the online row's `GroupItems=true`: group first, *then*
         * [limit], or a twenty-episode download fills the whole shelf.
         */
        override suspend fun getLatestMedia(
            parentId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val library = parentId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .latestDownloadedKeys(
                        source = ItemSource.DOWNLOAD,
                        types = typesOf(library, LIST_ITEM_TYPES),
                        episodeType = ItemType.EPISODE,
                    )
                    // Newest first already, so a group's surviving row is that show's latest download.
                    .distinctBy { it.groupId }
                    .take(limit)
                    .let { groups -> latestCards(groups).asHomeCards() }
            }

        private suspend fun latestCards(groups: List<LatestDownloadKey>): List<JellyfinItem> {
            if (groups.isEmpty()) return emptyList()
            val ids = (groups.map { it.groupId } + groups.map { it.id }).distinct()
            // Not filtered to downloads: a group collapses into its parent series, the one
            // exception to the downloads-only rule.
            val rows = itemDao.getItems(ids).associateBy { it.id }
            val userData: Map<UUID, UserDataEntity> =
                currentUserId()
                    ?.let { userDataDao.getUserDataFor(ids, it) }
                    ?.associateBy { it.itemId }
                    .orEmpty()

            return groups.mapNotNull { group ->
                val card = rows[group.groupId]
                if (group.groupId == group.id || card?.type == ItemType.SERIES) {
                    card?.let { mapper.toDomainOrNull(it, userData[it.id]) }
                } else {
                    rows[group.id]?.let { episode ->
                        mapper.toSeriesCardOrNull(episode)
                            ?: mapper.toDomainOrNull(episode, userData[episode.id])
                    }
                }
            }
        }

        // ---- library grid & search ---------------------------------------------------------

        /**
         * @param onTotalCount never called: the count Room could report is what is on the device,
         *   not the library size the header labels (see [ItemPage]).
         */
        override fun getItemsPaged(
            query: ItemQuery,
            onTotalCount: (Int) -> Unit,
        ): Flow<PagingData<JellyfinItem>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        initialLoadSize = ItemQuery.DEFAULT_PAGE_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemPagingSource(pageSize = ItemQuery.DEFAULT_PAGE_SIZE) { startIndex, limit, _ ->
                        getItems(query.copy(startIndex = startIndex, limit = limit))
                            .map { ItemPage(items = it) }
                    }
                },
            ).flow

        override suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>> =
            onIo {
                val requested = query.itemTypes.ifEmpty { LIST_ITEM_TYPES }
                val term = query.searchTerm?.trim()
                val rows =
                    if (!term.isNullOrEmpty()) {
                        // Deliberately library-wide and unfiltered: no surface sets both a search
                        // term and filters, and offline the item you downloaded matters more than
                        // which grid you were in.
                        itemDao.searchDownloaded(ItemSource.DOWNLOAD, requested, term, query.limit)
                    } else {
                        gridPage(query, requested)
                    }
                rows.withLocalUserData(currentUserId())
            }

        /**
         * Filter, *then* page — never a SQL `LIMIT` over the unfiltered set, because a short page is
         * how [ItemPagingSource] recognises the end of the library and the grid would stop early.
         * The filter cannot move into SQL: `genres` is a newline-joined column. `sortBy` degrades to
         * `sortName` offline (known limitation).
         */
        private suspend fun gridPage(
            query: ItemQuery,
            requested: List<ItemType>,
        ): List<ItemEntity> {
            val page =
                itemDao
                    .downloadedListKeys(
                        source = ItemSource.DOWNLOAD,
                        types = typesOf(query.parentId?.toUuidOrNull(), requested),
                        userId = currentUserId(),
                        descending = query.sortOrder == SortOrder.DESCENDING,
                    ).filter { it.matches(query.filters) }
                    .drop(query.startIndex)
                    .take(query.limit)

            if (page.isEmpty()) return emptyList()

            // `getItems` answers unordered; the statement's sort is re-imposed from the key list.
            val rows = itemDao.getItems(page.map { it.id }).associateBy { it.id }
            return page.mapNotNull { rows[it.id] }
        }

        /**
         * A projection, not whole rows: facets are distinct values over the whole offline library so
         * the query has no `LIMIT`, and full rows would deserialise every `dto` blob per sheet open.
         */
        override suspend fun getFilterFacets(
            parentId: String?,
            itemTypes: List<ItemType>,
        ): AppResult<FilterFacets> =
            onIo {
                val rows =
                    itemDao.facetKeysBySource(
                        ItemSource.DOWNLOAD,
                        typesOf(parentId?.toUuidOrNull(), itemTypes.ifEmpty { LIST_ITEM_TYPES }),
                    )
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

        override suspend fun getSeriesEpisodes(seriesId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val series = seriesId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .episodesOfSeries(ItemSource.DOWNLOAD, series, ItemType.EPISODE)
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

        /** Deliberately empty: a server recommendation over the whole library has no local stand-in. */
        override suspend fun getSimilarItems(
            id: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> = AppResult.Success(emptyList())

        // ---- music -----------------------------------------------------------------------------

        override suspend fun getAlbumTracks(albumId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val album = albumId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .tracksOfAlbum(ItemSource.DOWNLOAD, album, ItemType.AUDIO)
                    .withLocalUserData(currentUserId())
            }

        override suspend fun getArtistAlbums(artistId: String): AppResult<List<JellyfinItem>> =
            onIo {
                val artist = artistId.toUuidOrNull() ?: return@onIo emptyList()
                itemDao
                    .albumsOfArtist(ItemSource.DOWNLOAD, artist, ItemType.MUSIC_ALBUM)
                    .withLocalUserData(currentUserId())
            }

        /**
         * A local approximation, not the server's ranking. `UserDataMapper` does not persist
         * [UserData.playCount], so a track with local user-data ranks at `0` — below one never touched.
         */
        override suspend fun getArtistTopTracks(
            artistId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> =
            onIo {
                val artist = artistId.toUuidOrNull() ?: return@onIo emptyList()
                val albums = itemDao.albumsOfArtist(ItemSource.DOWNLOAD, artist, ItemType.MUSIC_ALBUM)
                val tracks =
                    albums.flatMap { album -> itemDao.tracksOfAlbum(ItemSource.DOWNLOAD, album.id, ItemType.AUDIO) }
                tracks
                    .withLocalUserData(currentUserId())
                    .sortedByDescending { it.userData.playCount }
                    .take(limit)
            }

        /** Deliberately empty: Room has no playlist-membership relation ("offline playlist membership"). */
        override suspend fun getPlaylistItems(playlistId: String): AppResult<List<JellyfinItem>> =
            AppResult.Success(emptyList())

        // ---- Continue Listening ---------------------------------------------------------------

        override suspend fun getResumeAudioItems(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val userId = currentUserId() ?: return@onIo emptyList()
                itemDao
                    .resumeDownloadedAudio(ItemSource.DOWNLOAD, userId, ItemType.AUDIO, limit)
                    .withLocalUserData(userId)
                    .asHomeCards()
            }

        // ---- Instant Mix & lyrics ---------------------------------------------------------------

        /**
         * Always refuses: a server recommendation has no offline analog. [AppError.Network] rather
         * than a new case — the same answer `PlaybackSourceResolver` gives for an undownloaded track.
         */
        override suspend fun getInstantMix(
            itemId: String,
            limit: Int,
        ): AppResult<List<JellyfinItem>> = AppResult.Failure(AppError.Network())

        /** Lyrics are not cached offline ("offline lyrics" is a deferred item). */
        override suspend fun getLyrics(itemId: String): AppResult<Lyrics> = AppResult.Failure(AppError.Network())

        // ---- helpers -------------------------------------------------------------------------

        /** Offline, `user_data` outranks the cached blob: a position written offline exists nowhere else. */
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
         * Attributes an offline row to a library by *type*, because parent-id filtering cannot work:
         * a downloaded row's `parentId` is its containing folder, or `NULL`, never the library-view
         * id the grid asks about. A v1 simplification — two movie libraries would share downloads.
         * An unknown library id narrows nothing rather than emptying the grid.
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

        private suspend fun <T> onIo(block: suspend () -> T): AppResult<T> =
            withContext(ioDispatcher) {
                try {
                    AppResult.Success(block())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: SQLiteException) {
                    Timber.e(error, "Offline read failed")
                    AppResult.Failure(AppError.Storage(error))
                }
            }

        private companion object {
            val LIST_ITEM_TYPES = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)

            val MOVIE_LIBRARY_TYPES = listOf(ItemType.MOVIE)

            /** Episodes are here because Latest lists them; the grid asks only for series. */
            val TV_LIBRARY_TYPES = listOf(ItemType.SERIES, ItemType.EPISODE)
        }
    }

/**
 * A download's cached blob is the *full* item, but online cards are fetched without `OVERVIEW`, so
 * the *Continue watching* hero grew a paragraph offline. Dropping it here keeps cached and fetched
 * cards indistinguishable downstream. `getItem` is untouched — detail is where the overview belongs.
 */
private fun List<JellyfinItem>.asHomeCards(): List<JellyfinItem> =
    map { if (it.overview == null) it else it.copy(overview = null) }

private fun unavailable(id: String): JellyfinItem =
    JellyfinItem(id = id, name = "", type = ItemType.UNKNOWN, available = false)

/** OR within a facet, AND across facets — the server's and jellyfin-web's reading of a filter. */
private fun DownloadedItemKey.matches(filters: FilterOptions): Boolean =
    (filters.genres.isEmpty() || genres.any { it in filters.genres }) &&
        (filters.years.isEmpty() || productionYear in filters.years) &&
        (filters.officialRatings.isEmpty() || officialRating in filters.officialRatings) &&
        (filters.isPlayed == null || played == filters.isPlayed) &&
        (filters.isFavorite == null || isFavorite == filters.isFavorite)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
