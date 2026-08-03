package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.entities.DownloadedItemKey
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemParentRefs
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LatestDownloadKey
import java.time.Instant
import java.util.UUID

/**
 * Data access for [ItemEntity] — the browse cache and the offline library.
 *
 * This DAO is deliberately **dumb**: it holds queries and nothing else. In particular the rule
 * that a browse write-through may never downgrade a [ItemSource.DOWNLOAD] row lives in
 * `:data`'s `BrowseCacheWriter`, not here, so that it can be unit-tested on the JVM rather than
 * only on a device.
 *
 * Every list query filters on an explicit `source` parameter instead of hard-coding
 * `'DOWNLOAD'`: the offline home/library/search surfaces are downloaded-items-only (docs/PLAN.md,
 * "Confirmed decisions" → offline browse scope) while `getItem` deliberately also serves cached
 * rows, and making the caller say which it wants keeps that distinction visible at the call site.
 */
@Dao
interface ItemDao {
    /** Inserts the rows, replacing any existing row with the same id. */
    @Upsert
    suspend fun upsert(items: List<ItemEntity>)

    /**
     * Reads the source and cache timestamp of the given ids, without their `dto` blobs.
     *
     * This is what the write-through merge consults before overwriting anything.
     */
    @Query("SELECT id, source, cachedAt FROM items WHERE id IN (:ids)")
    suspend fun getCacheKeys(ids: List<UUID>): List<ItemCacheKey>

    /** One item regardless of source — cached parents of downloads must open offline. */
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItem(id: UUID): ItemEntity?

    /** Several items regardless of source, in no particular order. */
    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getItems(ids: List<UUID>): List<ItemEntity>

    /**
     * The parent links of the given items, without their `dto` blobs.
     *
     * What the delete cascade's orphan prune walks: it needs only each surviving download's
     * series/season/folder ids, and reading them through [getItems] materialised every survivor's
     * multi-kilobyte blob once per deleted item (audit DL-05).
     */
    @Query("SELECT id, parentId, seriesId, seasonId FROM items WHERE id IN (:ids)")
    suspend fun getParentRefs(ids: List<UUID>): List<ItemParentRefs>

    /**
     * The offline library grid's whole result set, in sort order, as the columns a **filter** is
     * decided from — and nothing else.
     *
     * **There is deliberately no library predicate.** It used to filter on
     * `parentId = <library id>` (plus `seriesId IN (children of the library)`), and on a real device
     * that returned nothing: a downloaded row's `parentId` is its *containing folder* — when the
     * server sends one at all; the M7 walk found both downloaded films stored with `parentId NULL` —
     * and a folder is not the library-view id the grid filters by. Which library an offline row
     * belongs to is decided by its **type** instead, in `OfflineJellyfinRepository`, which is exact
     * for the movie/TV libraries v1 supports (DECISIONS.md 2026-07-28).
     *
     * **No `LIMIT`, and no whole rows.** The filters the grid applies are not all expressible in
     * one statement — `genres` is a newline-joined column, and SQLite has no way to intersect it
     * with a bound list — so the predicate is applied in Kotlin, and a `LIMIT` here would page over
     * the *unfiltered* set: a page could come back short and Paging would read that as the end of
     * the library (`ItemPagingSource`). Reading the whole set is affordable precisely because this
     * projection leaves out the multi-kilobyte `dto` blob; the page's blobs are then read by
     * [getItems], the same shape [latestDownloadedKeys] uses.
     *
     * The `user_data` join is a `LEFT JOIN` with `COALESCE`, so an item this user has never played
     * is *unwatched* rather than missing — which is what the watched/unwatched filter has to mean.
     *
     * @param userId whose playback state the watched/favourite columns describe; `null` (nobody
     *   signed in) leaves every row unwatched and unfavourited.
     * @param descending `true` sorts Z→A; the two `CASE` arms are how one statement serves both
     *   directions (SQLite cannot bind a sort direction).
     */
    @Query(
        """
        SELECT
          i.id AS id,
          i.genres AS genres,
          i.productionYear AS productionYear,
          i.officialRating AS officialRating,
          COALESCE(u.played, 0) AS played,
          COALESCE(u.isFavorite, 0) AS isFavorite
        FROM items AS i
        LEFT JOIN user_data AS u ON u.itemId = i.id AND u.userId = :userId
        WHERE i.source = :source
          AND i.type IN (:types)
        ORDER BY
          CASE WHEN :descending = 0 THEN i.sortName END COLLATE NOCASE ASC,
          CASE WHEN :descending = 1 THEN i.sortName END COLLATE NOCASE DESC
        """,
    )
    suspend fun downloadedListKeys(
        source: ItemSource,
        types: List<ItemType>,
        userId: UUID?,
        descending: Boolean,
    ): List<DownloadedItemKey>

    /**
     * Offline search. Matches the item's own name and — so that typing a show's title finds its
     * downloaded episodes — the series name.
     */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source
          AND type IN (:types)
          AND (name LIKE '%' || :term || '%' OR seriesName LIKE '%' || :term || '%')
        ORDER BY sortName COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun searchDownloaded(
        source: ItemSource,
        types: List<ItemType>,
        term: String,
        limit: Int,
    ): List<ItemEntity>

    /**
     * The offline *Continue watching* row: downloaded items this device has a resume position for,
     * most recently played first.
     */
    @Query(
        """
        SELECT i.* FROM items AS i
        INNER JOIN user_data AS u ON u.itemId = i.id AND u.userId = :userId
        WHERE i.source = :source
          AND u.playbackPositionTicks > 0
          AND u.played = 0
        ORDER BY u.lastPlayedDate DESC, u.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun resumeDownloaded(
        source: ItemSource,
        userId: UUID,
        limit: Int,
    ): List<ItemEntity>

    /**
     * Every downloaded episode this device has neither played nor started, in broadcast order.
     *
     * The "one per series" reduction that produces the *Next up* row is done in Kotlin: SQLite's
     * grouped-aggregate row selection is implementation-defined for the non-aggregated columns,
     * and picking the first of an ordered list is trivially testable.
     */
    @Query(
        """
        SELECT i.* FROM items AS i
        LEFT JOIN user_data AS u ON u.itemId = i.id AND u.userId = :userId
        WHERE i.source = :source
          AND i.type = :episodeType
          AND (:seriesId IS NULL OR i.seriesId = :seriesId)
          AND COALESCE(u.played, 0) = 0
          AND COALESCE(u.playbackPositionTicks, 0) = 0
        ORDER BY i.seriesName COLLATE NOCASE ASC, i.parentIndexNumber ASC, i.indexNumber ASC
        """,
    )
    suspend fun unwatchedDownloadedEpisodes(
        source: ItemSource,
        userId: UUID,
        episodeType: ItemType,
        seriesId: UUID?,
    ): List<ItemEntity>

    /**
     * The ordering keys of the offline *Latest* row: every downloaded item of the given kinds,
     * newest first, each carrying the id of the card it belongs to.
     *
     * Scoped by type rather than by library id, for the reason [pagingDownloaded] documents.
     *
     * **No `LIMIT`, and no whole rows.** Episodes collapse into their series before the row limit
     * applies (see [LatestDownloadKey]), so the statement cannot stop at sixteen rows and the
     * caller cannot afford to read sixteen — let alone every — `dto` blob to find that out. The
     * "one row per series" reduction itself is done in Kotlin, for the reason
     * [unwatchedDownloadedEpisodes] gives.
     */
    @Query(
        """
        SELECT
          id AS id,
          CASE
            WHEN type = :episodeType AND seriesId IS NOT NULL THEN seriesId
            ELSE id
          END AS groupId
        FROM items
        WHERE source = :source
          AND type IN (:types)
        ORDER BY cachedAt DESC
        """,
    )
    suspend fun latestDownloadedKeys(
        source: ItemSource,
        types: List<ItemType>,
        episodeType: ItemType,
    ): List<LatestDownloadKey>

    /**
     * A downloaded series' seasons, in server order.
     *
     * Matches on `seriesId` **or** `parentId` because only the first is reliable: a season's
     * `ParentId` is not always present on the cached DTO (the same gap that made the library grid
     * empty — see [pagingDownloaded]), while `SeriesId` is what identifies a season's show and is
     * what the episode rows already join on.
     */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source
          AND type = :seasonType
          AND (seriesId = :seriesId OR parentId = :seriesId)
        ORDER BY indexNumber ASC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun seasonsOfSeries(
        source: ItemSource,
        seriesId: UUID,
        seasonType: ItemType,
    ): List<ItemEntity>

    /** A season's downloaded episodes, in broadcast order. */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source AND type = :episodeType AND seasonId = :seasonId
        ORDER BY indexNumber ASC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun episodesOfSeason(
        source: ItemSource,
        seasonId: UUID,
        episodeType: ItemType,
    ): List<ItemEntity>

    /**
     * A series' downloaded episodes, in broadcast order across seasons.
     *
     * The season-spanning sibling of [episodesOfSeason]: ordering by `parentIndexNumber` first puts
     * season 1 before season 2 before the specials the server numbers 0, which is what "everything
     * after this episode" means to a viewer — and to jellyfin-web, whose expansion of a one-episode
     * SyncPlay queue this has to match entry for entry.
     */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source AND type = :episodeType AND seriesId = :seriesId
        ORDER BY parentIndexNumber ASC, indexNumber ASC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun episodesOfSeries(
        source: ItemSource,
        seriesId: UUID,
        episodeType: ItemType,
    ): List<ItemEntity>

    /** Every downloaded row of the given types — the input to the offline filter facets. */
    @Query("SELECT * FROM items WHERE source = :source AND type IN (:types)")
    suspend fun allBySource(
        source: ItemSource,
        types: List<ItemType>,
    ): List<ItemEntity>

    /**
     * Drops browse-cache rows older than [cutoff].
     *
     * Downloads are excluded by the `source` predicate rather than by the timestamp: a
     * [ItemSource.DOWNLOAD] row is never evicted, however stale it looks.
     */
    @Query("DELETE FROM items WHERE source = :browseCache AND cachedAt < :cutoff")
    suspend fun evictBrowseCacheOlderThan(
        cutoff: Instant,
        browseCache: ItemSource,
    ): Int

    // ---- M7 — download-delete cascade ----------------------------------------------------------

    /**
     * Drops the [ItemSource.DOWNLOAD] rows that no download points at any more.
     *
     * [keep] is the whole reason this takes a list rather than a single id: deleting one episode
     * must not remove the series and season rows its *siblings* still need in order to open
     * offline. `:data:downloads`' `DownloadDeleter` computes the surviving set (every remaining
     * download plus each one's series and season) and hands it in.
     *
     * A row that is still worth caching is not lost by this — it is simply no longer *guaranteed*:
     * the browse cache re-creates it as [ItemSource.BROWSE_CACHE] the next time the user browses
     * past it.
     *
     * @return how many rows were dropped.
     */
    @Query("DELETE FROM items WHERE source = :download AND id NOT IN (:keep)")
    suspend fun deleteDownloadsNotIn(
        keep: List<UUID>,
        download: ItemSource,
    ): Int
}
