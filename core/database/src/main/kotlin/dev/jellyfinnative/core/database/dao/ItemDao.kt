package dev.jellyfinnative.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.entities.ItemCacheKey
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
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
@Suppress("TooManyFunctions")
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
     * One page of the offline library grid.
     *
     * The `parentId` predicate walks one level of the hierarchy on purpose: a movie's parent *is*
     * the library, but a downloaded episode's parent is its season, so a library also owns every
     * item whose series it owns.
     *
     * @param descending `true` sorts Z→A; the two `CASE` arms are how one statement serves both
     *   directions (SQLite cannot bind a sort direction).
     */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source
          AND type IN (:types)
          AND (
            :parentId IS NULL
            OR parentId = :parentId
            OR seriesId IN (SELECT id FROM items WHERE parentId = :parentId)
          )
        ORDER BY
          CASE WHEN :descending = 0 THEN sortName END COLLATE NOCASE ASC,
          CASE WHEN :descending = 1 THEN sortName END COLLATE NOCASE DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    // SQLite binds parameters positionally; a query with six filters simply has six of them, and
    // wrapping them in a value class would only move the list one call further away.
    @Suppress("LongParameterList")
    suspend fun pagingDownloaded(
        source: ItemSource,
        types: List<ItemType>,
        parentId: UUID?,
        descending: Boolean,
        limit: Int,
        offset: Int,
    ): List<ItemEntity>

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

    /** The offline *Latest* row for one library: its most recently downloaded items. */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source
          AND type IN (:types)
          AND (
            :parentId IS NULL
            OR parentId = :parentId
            OR seriesId IN (SELECT id FROM items WHERE parentId = :parentId)
          )
        ORDER BY cachedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun latestDownloaded(
        source: ItemSource,
        types: List<ItemType>,
        parentId: UUID?,
        limit: Int,
    ): List<ItemEntity>

    /** Children of one parent (a series' seasons, a season's episodes), in server order. */
    @Query(
        """
        SELECT * FROM items
        WHERE source = :source AND type = :type AND parentId = :parentId
        ORDER BY indexNumber ASC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun childrenOf(
        source: ItemSource,
        type: ItemType,
        parentId: UUID,
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
}
