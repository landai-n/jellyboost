package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.entities.DownloadedItemKey
import dev.jellyboost.core.database.entities.FacetKey
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemParentRefs
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LatestDownloadKey
import java.time.Instant
import java.util.UUID

@Dao
@Suppress(
    // One member per query — a DAO is wide by contract.
    "TooManyFunctions",
)
interface ItemDao {
    @Upsert
    suspend fun upsert(items: List<ItemEntity>)

    @Query("SELECT id, source, cachedAt FROM items WHERE id IN (:ids)")
    suspend fun getCacheKeys(ids: List<UUID>): List<ItemCacheKey>

    /** Deliberately unfiltered by source: cached parents of downloads must open offline. */
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItem(id: UUID): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getItems(ids: List<UUID>): List<ItemEntity>

    /** A projection, not [getItems]: the orphan prune must not materialise every survivor's `dto` blob. */
    @Query("SELECT id, parentId, seriesId, seasonId, albumId, albumArtistId FROM items WHERE id IN (:ids)")
    suspend fun getParentRefs(ids: List<UUID>): List<ItemParentRefs>

    /**
     * **There is deliberately no library predicate.** A downloaded row's `parentId` is its containing folder
     * (when the server sends one at all — downloaded films can have it `NULL`), never the library-view id;
     * `OfflineJellyfinRepository` decides library membership by **type** instead.
     *
     * **No `LIMIT`, and no whole rows.** `genres` is a newline-joined column SQLite cannot intersect with a
     * bound list, so the filter runs in Kotlin; a `LIMIT` here would page the *unfiltered* set and a short
     * page reads as the end of the library in `ItemPagingSource`. Affordable only because the `dto` blob is
     * left out. The `LEFT JOIN`/`COALESCE` makes a never-played item *unwatched* rather than missing.
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

    @Query(
        """
        SELECT i.* FROM items AS i
        INNER JOIN user_data AS u ON u.itemId = i.id AND u.userId = :userId
        WHERE i.source = :source
          AND i.type = :audioType
          AND u.playbackPositionTicks > 0
          AND u.played = 0
        ORDER BY u.lastPlayedDate DESC, u.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun resumeDownloadedAudio(
        source: ItemSource,
        userId: UUID,
        audioType: ItemType,
        limit: Int,
    ): List<ItemEntity>

    /**
     * The "one per series" reduction behind *Next up* is done in Kotlin: SQLite's grouped-aggregate row
     * selection is implementation-defined for the non-aggregated columns.
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
     * **No `LIMIT`, and no whole rows.** Episodes collapse into their series before the row limit applies
     * (see [LatestDownloadKey]), so the statement cannot stop at sixteen rows and the caller must not read
     * sixteen `dto` blobs to discover that.
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
     * Matches `seriesId` **or** `parentId` because only the first is reliable: a season's `ParentId` is not
     * always present on the cached DTO.
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
     * `parentIndexNumber` first puts season 1 before season 2 before the specials the server numbers 0 — this
     * ordering has to match jellyfin-web's expansion of a one-episode SyncPlay queue entry for entry.
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

    @Query(
        """
        SELECT * FROM items
        WHERE source = :source AND type = :audioType AND albumId = :albumId
        ORDER BY parentIndexNumber ASC, indexNumber ASC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun tracksOfAlbum(
        source: ItemSource,
        albumId: UUID,
        audioType: ItemType,
    ): List<ItemEntity>

    @Query(
        """
        SELECT * FROM items
        WHERE source = :source AND type = :albumType AND albumArtistId = :artistId
        ORDER BY productionYear DESC, sortName COLLATE NOCASE ASC
        """,
    )
    suspend fun albumsOfArtist(
        source: ItemSource,
        artistId: UUID,
        albumType: ItemType,
    ): List<ItemEntity>

    /**
     * No `LIMIT`: a facet list is the *distinct* values across the whole offline library, and a page of it
     * would offer filters that exclude items the user can see. Three columns, so no `dto` blob is deserialised.
     */
    @Query(
        """
        SELECT genres AS genres, productionYear AS productionYear, officialRating AS officialRating
        FROM items
        WHERE source = :source AND type IN (:types)
        """,
    )
    suspend fun facetKeysBySource(
        source: ItemSource,
        types: List<ItemType>,
    ): List<FacetKey>

    /** A [ItemSource.DOWNLOAD] row is never evicted, however stale it looks — hence the `source` predicate. */
    @Query("DELETE FROM items WHERE source = :browseCache AND cachedAt < :cutoff")
    suspend fun evictBrowseCacheOlderThan(
        cutoff: Instant,
        browseCache: ItemSource,
    ): Int

    /**
     * Bounds what age cannot: one session of heavy browsing writes rows far faster than a month passes, and
     * every row carries a multi-kilobyte `dto` blob. `LIMIT -1` is SQLite's "no limit", the only way to
     * express an `OFFSET` without one. Downloads are excluded in both halves and are never evicted.
     */
    @Query(
        """
        DELETE FROM items
        WHERE source = :browseCache
          AND id IN (
            SELECT id FROM items
            WHERE source = :browseCache
            ORDER BY cachedAt DESC
            LIMIT -1 OFFSET :keep
          )
        """,
    )
    suspend fun trimBrowseCacheTo(
        keep: Int,
        browseCache: ItemSource,
    ): Int

    /**
     * Sign-out must clear this: the `items` table is not user-scoped, so on a shared device one account's
     * cached browsing would keep serving the next account. Downloads are excluded — signing out deletes
     * nobody's files.
     */
    @Query("DELETE FROM items WHERE source = :browseCache")
    suspend fun deleteAllBrowseCache(browseCache: ItemSource): Int

    /**
     * [keep] is a list, not one id: deleting one episode must not remove the series and season rows its
     * *siblings* still need in order to open offline. `DownloadDeleter` computes the surviving set.
     */
    @Query("DELETE FROM items WHERE source = :download AND id NOT IN (:keep)")
    suspend fun deleteDownloadsNotIn(
        keep: List<UUID>,
        download: ItemSource,
    ): Int
}
