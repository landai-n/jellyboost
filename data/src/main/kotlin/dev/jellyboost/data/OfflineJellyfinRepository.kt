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
 * | Latest *library* | the most recently downloaded items of that library, episodes grouped into their series |
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
@Suppress(
    // One member per [JellyfinRepository] method, by construction — same rationale as
    // `OnlineJellyfinRepository`'s identical suppression. M13 Phase 2's four music members
    // (docs/notes/music-m13-plan.md) pushed this class from 19 to 23; Phase 4's `getResumeAudioItems`
    // to 24. Logged in DECISIONS.md.
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
                    // The query is already ordered by series then season then episode, so the first
                    // row of each series *is* its next episode.
                    .distinctBy { it.seriesId }
                    .take(limit)
                    .withLocalUserData(userId)
                    .asHomeCards()
            }

        /**
         * The offline *Latest* shelf — **one card per series**, exactly like the online row.
         *
         * The server groups a TV library's new episodes into their show (`GroupItems`), so online
         * the shelf shows one poster per series however many episodes arrived. Offline the shelf
         * used to list the downloaded rows raw, which meant a downloaded season filled it with its
         * own episodes. The reduction happens here instead: downloads are read newest-first as
         * [dev.jellyboost.core.database.entities.LatestDownloadKey]s, the first row of each
         * group wins, and only then does [limit] apply — a series with twenty episodes takes one
         * slot, not twenty. Movies group onto themselves and are unaffected.
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
                    // Newest first already, so the surviving row of a group is that show's most
                    // recent download — the position `GroupItems=true` gives it online.
                    .distinctBy { it.groupId }
                    .take(limit)
                    .let { groups -> latestCards(groups).asHomeCards() }
            }

        /**
         * Turns grouped [LatestDownloadKey]s into the cards the shelf draws, in the order given.
         *
         * A group's card is, in order of preference:
         * 1. the **series' own cached row** — the download pipeline caches an episode's series and
         *    season alongside it, so this is the normal case and the card is a real item with a
         *    working detail page;
         * 2. a card **synthesised from the episode** when that parent fetch failed (it is best
         *    effort), so the shelf still shows one poster per show;
         * 3. the episode itself, for the pathological row that names no series at all — a card
         *    that is slightly wrong beats a download that vanished from the shelf.
         */
        private suspend fun latestCards(groups: List<LatestDownloadKey>): List<JellyfinItem> {
            if (groups.isEmpty()) return emptyList()
            val ids = (groups.map { it.groupId } + groups.map { it.id }).distinct()
            // Deliberately *not* filtered to downloads: the card a group collapses into is the
            // parent series, and "cached parents of downloaded items still open" is the plan's one
            // exception to the downloads-only rule (docs/PLAN.md, "Confirmed decisions").
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
         * @param onTotalCount never called: Room holds the *downloaded* items, so the only count
         *   this source could report is the number of items on the device — which is not the
         *   number of items in the library the header would be labelling (see [ItemPage]).
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
                // Deliberately the same `ItemPagingSource` the online grid uses rather than a
                // Room-generated `PagingSource`: the offset/limit contract is identical, and one
                // paging implementation means one set of edge cases (see DECISIONS.md).
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
                        // Search is deliberately library-wide: offline there are a handful of items
                        // and finding one you downloaded matters more than which grid you were in.
                        // It carries no filters because no surface sets both — the filter sheet
                        // belongs to the library grid, and the search screen has none.
                        itemDao.searchDownloaded(ItemSource.DOWNLOAD, requested, term, query.limit)
                    } else {
                        gridPage(query, requested)
                    }
                rows.withLocalUserData(currentUserId())
            }

        /**
         * One page of the offline library grid, filters and all.
         *
         * Filter, *then* page — not the other way round. `query.filters` used to be ignored here
         * entirely: the grid re-queried on every *Apply* and rendered the identical unfiltered list
         * under an "1 active" badge (docs/notes/audit-2026-07.md, ARCH-01). It cannot be done in the
         * statement because `genres` is a newline-joined column with no SQL intersection against a
         * bound list, and a half-SQL/half-Kotlin predicate would be worse than either: a `LIMIT`
         * over the unfiltered set returns short pages, and a short page is how `ItemPagingSource`
         * recognises the end of the library — the grid would simply stop early.
         *
         * So the ordering and the cheap columns come from Room ([ItemDao.downloadedListKeys], which
         * carries no `dto` blob), the whole set is narrowed here, and only the surviving page's rows
         * are read in full. `sortBy` still degrades to `sortName` offline — that half is the logged
         * divergence (DECISIONS.md 2026-07-28) and is untouched.
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

            // `getItems` answers in no particular order, so the sort the statement applied is
            // re-imposed from the key list rather than re-derived.
            val rows = itemDao.getItems(page.map { it.id }).associateBy { it.id }
            return page.mapNotNull { rows[it.id] }
        }

        /**
         * The facets the offline filter sheet offers — scoped to the library that asked.
         *
         * [parentId] used to be ignored, so a film library's sheet listed the genres of downloaded
         * *television* and filtering by one of them could only ever produce an empty grid
         * (docs/notes/audit-2026-07.md, ARCH-01). Scoping is by type, for the reason
         * [typesOf] documents.
         *
         * The read is a three-column projection ([FacetKey]) and not [ItemEntity]: a facet list is
         * the distinct values over the *whole* offline library, so this query has no `LIMIT` — and
         * as whole rows it deserialised every downloaded item's multi-kilobyte `dto` blob to
         * produce three small lists, each time the sheet was opened (audit 2026-08-08, PERF-18).
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

        /**
         * Only the *downloaded* episodes, which is the honest offline answer: a group queue built
         * from here skips what this device never fetched. The caller's fallback covers the case
         * where that leaves nothing usable.
         */
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

        // ---- M13 Phase 2 — music --------------------------------------------------------------

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
         * Offline "top tracks": this device's downloaded tracks of the artist, ranked by whatever
         * play count each track's cached blob or local user-data carries.
         *
         * There is no per-track play-count query and asking a handful of downloaded songs for the
         * server's actual top tracks would not be honest, so this walks every downloaded album of
         * the artist ([ItemDao.albumsOfArtist]), reads each one's downloaded tracks
         * ([ItemDao.tracksOfAlbum]), and sorts what it finds by [UserData.playCount] — a documented
         * local approximation, not the server's ranking (see
         * [JellyfinRepository.getArtistTopTracks]'s KDoc).
         *
         * One caveat worth recording: [UserData.playCount] on a track this device has *locally*
         * written user data for (a favourite toggle, a played flag) reads `0` rather than the
         * server's cached count — `UserDataMapper` deliberately does not persist `playCount` locally
         * (docs/PLAN.md) — so a track this device interacted with can rank below one it never
         * touched. Acceptable for an offline approximation; not worth a schema change here.
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

        /**
         * Always empty offline — see [JellyfinRepository.getPlaylistItems]'s KDoc: Room has no
         * playlist-membership relation, so there is no set of downloaded tracks that can honestly be
         * called "this playlist's members" before M13 Phase 5 gives playlists their own offline
         * model.
         */
        override suspend fun getPlaylistItems(playlistId: String): AppResult<List<JellyfinItem>> =
            AppResult.Success(emptyList())

        // ---- M13 Phase 4 — Continue Listening ---------------------------------------------------

        /** [getResumeItems]'s audio counterpart — see [ItemDao.resumeDownloadedAudio]'s KDoc. */
        override suspend fun getResumeAudioItems(limit: Int): AppResult<List<JellyfinItem>> =
            onIo {
                val userId = currentUserId() ?: return@onIo emptyList()
                itemDao
                    .resumeDownloadedAudio(ItemSource.DOWNLOAD, userId, ItemType.AUDIO, limit)
                    .withLocalUserData(userId)
                    .asHomeCards()
            }

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
                } catch (error: SQLiteException) {
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
 * Narrows detail-shaped rows to the **card shape the home screen is drawn for**.
 *
 * The two paths into the home rows do not carry the same amount of item. Online they are fetched
 * with `OnlineJellyfinRepository.CARD_FIELDS`, which asks the server for exactly one extra field —
 * the primary image aspect ratio — and deliberately not for `OVERVIEW` (`OnlineJellyfinRepository`'s
 * own test pins that). Offline they are read back out of a cached `BaseItemDto`, and a download's
 * blob is the *full* item: the enqueue fetch asks for `OVERVIEW, GENRES, PEOPLE, …` so that the
 * detail page works with no server (docs/PLAN.md, "Downloads"). So the identical screen was handed
 * a synopsis offline and none online, and the wide *Continue watching* hero — the one card that
 * draws an overview — grew by a paragraph offline and pushed its resume button over the row
 * beneath it.
 *
 * Dropping the field here rather than hiding it in the UI keeps the plan's promise that a cached
 * item and a fetched one are indistinguishable downstream (docs/PLAN.md, "Data layer"): the home
 * rows answer with the same shape from either source, so the hero has no way to look different
 * offline. `getItem` is untouched — the offline *detail* page is precisely where the blob's
 * overview is meant to be read.
 */
private fun List<JellyfinItem>.asHomeCards(): List<JellyfinItem> =
    map { if (it.overview == null) it else it.copy(overview = null) }

/**
 * The answer for an item we do not have.
 *
 * `available = false` is `JellyfinItem`'s own vocabulary for "known of, but not openable"; the
 * plan requires it here instead of an error (docs/PLAN.md, "Data layer" →
 * `OfflineJellyfinRepository`).
 */
private fun unavailable(id: String): JellyfinItem =
    JellyfinItem(id = id, name = "", type = ItemType.UNKNOWN, available = false)

/**
 * Whether a downloaded row survives [filters].
 *
 * Values inside one facet are OR-ed and the facets are AND-ed — jellyfin-web's own reading, and the
 * server's, so a filter means the same thing offline as online. An empty facet is not a filter at
 * all: [FilterOptions.isEmpty] holding means every row matches.
 */
private fun DownloadedItemKey.matches(filters: FilterOptions): Boolean =
    (filters.genres.isEmpty() || genres.any { it in filters.genres }) &&
        (filters.years.isEmpty() || productionYear in filters.years) &&
        (filters.officialRatings.isEmpty() || officialRating in filters.officialRatings) &&
        (filters.isPlayed == null || played == filters.isPlayed) &&
        (filters.isFavorite == null || isFavorite == filters.isFavorite)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
