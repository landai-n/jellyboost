package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.jellyboost.core.common.model.ItemType
import java.time.Instant
import java.util.UUID

/**
 * Why a row exists in the `items` table — and therefore whether it may ever be evicted.
 *
 * The distinction is the backbone of the offline read path (docs/PLAN.md, "Data layer"):
 * everything the user browses online is cached opportunistically, but only what they explicitly
 * downloaded is guaranteed to still be there when the network is not.
 */
enum class ItemSource {
    /**
     * Written through by `OnlineJellyfinRepository` after a successful server read. Disposable:
     * these rows exist so that a *cached parent* of a download (a series page, a season) can still
     * be opened offline, and they are the first thing an eviction pass removes.
     */
    BROWSE_CACHE,

    /**
     * The item is downloaded, or is the series/season parent of a downloaded episode (M7 upserts
     * both). **Never evicted, and never downgraded to [BROWSE_CACHE]** by a later browse — losing
     * this row would orphan the files on disk.
     */
    DOWNLOAD,
}

/**
 * One media item, cached locally.
 *
 * Single table for every item kind ([D] in docs/PLAN.md — deliberately not Findroid's four typed
 * tables), split into two halves with different jobs:
 *
 * - **structured columns** exist purely so that lists can be *queried*: filtered by type/parent,
 *   sorted by name, searched, grouped by series. Nothing reconstructs a domain model from them.
 * - **[dto]** is the complete `BaseItemDto` as JSON (media sources, streams, chapters, trickplay,
 *   people). It is what an item is actually rebuilt from, so an offline `JellyfinItem` is produced
 *   by the *same* mapper as an online one — the mechanism behind one seamless UI.
 *
 * Image *tags* rather than image URLs are stored: a URL embeds the server's base address, which
 * changes when the reachability probe rotates to another `ServerAddressEntity`.
 *
 * ### Why these indices, and why not the obvious ones (schema v9)
 *
 * Every list query in [dev.jellyboost.core.database.dao.ItemDao] leads with `source`, because the
 * offline surfaces are downloaded-items-only while the same table also holds the whole browse
 * cache. So the two composites below are what the statements are actually shaped like, and the
 * single-column `source` / `cachedAt` indices they replace are not kept alongside them: `source` is
 * an exact leftmost prefix of `(source, type)`, and no query orders or filters by `cachedAt`
 * without also fixing `source`. Verified with `EXPLAIN QUERY PLAN` on a 20k-row copy of this
 * schema — dropping both leaves every plan byte-identical (audit 2026-08-08, PERF-3/PERF-24).
 *
 * - `(source, type)` turned four full-ish scans into two-column searches. They previously picked
 *   `index_items_type` and then visited **every** browsed episode ever cached, deserialising a
 *   multi-kilobyte [dto] blob per row before `source` discarded it (`unwatchedDownloadedEpisodes`,
 *   `searchDownloaded`, `downloadedListKeys`, `facetKeysBySource`).
 * - `(source, cachedAt)` serves the browse-cache eviction sweep as a two-column range delete
 *   instead of a scan of *every* row older than the cutoff regardless of source, and lets
 *   `latestDownloadedKeys` read its `cachedAt DESC` order straight off the index — its
 *   `TEMP B-TREE` is gone.
 * - `type` stays as a single column even though it is the *second* member of `(source, type)`: a
 *   composite cannot answer a type-only predicate, and unlike `source` it has no covering prefix
 *   to fall back on.
 * - **There is deliberately no `sortName` index.** Room's `@Index` cannot express a collation, so
 *   the one this table used to carry was `BINARY` while both consumers sort
 *   `sortName COLLATE NOCASE` — proven never used (the NOCASE plans build a `TEMP B-TREE` and
 *   ignore it), i.e. pure write amplification on every cached page. The sorts it would have served
 *   run over the `source`-filtered subset, which the composite above now bounds.
 *
 * @property sortName the server's `sortName`, falling back to [name]; the library grid's sort key.
 * @property cachedAt when this row was last written. Doubles as the "recently downloaded" ordering
 *   for the offline home rows, which is why a browse write-through must not bump it on a
 *   [ItemSource.DOWNLOAD] row.
 */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["source", "type"]),
        Index(value = ["source", "cachedAt"]),
        Index(value = ["type"]),
        Index(value = ["parentId"]),
        Index(value = ["seriesId"]),
        Index(value = ["seasonId"]),
        Index(value = ["albumId"]),
        Index(value = ["albumArtistId"]),
    ],
)
data class ItemEntity(
    @PrimaryKey
    val id: UUID,
    val name: String,
    val sortName: String,
    val type: ItemType,
    val source: ItemSource,
    val cachedAt: Instant,
    val productionYear: Int? = null,
    val premiereDate: Instant? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val runTimeTicks: Long? = null,
    /** Episode number within its season. */
    val indexNumber: Int? = null,
    /** Season number for an episode. */
    val parentIndexNumber: Int? = null,
    val parentId: UUID? = null,
    val seriesId: UUID? = null,
    val seriesName: String? = null,
    val seasonId: UUID? = null,
    val genres: List<String> = emptyList(),
    val primaryImageTag: String? = null,
    val backdropImageTag: String? = null,
    val thumbImageTag: String? = null,
    val logoImageTag: String? = null,
    val primaryImageAspectRatio: Double? = null,
    /**
     * The album a track belongs to (M13). Query-only, like [albumArtistId] below: it exists so an
     * offline "tracks of this album, in order" query can filter and sort in SQL, but a domain item
     * still rebuilds from [dto] like everything else — there is no shortcut back the other way.
     */
    val albumId: UUID? = null,
    /** The id of a track's album's artist, or an album's own artist (M13). Query-only, see [albumId]. */
    val albumArtistId: UUID? = null,
    /** The complete `BaseItemDto` as JSON — the only thing a domain item is rebuilt from. */
    val dto: String,
)

/**
 * Projection of just the two columns the write-through merge rule needs.
 *
 * Reading whole rows (each carrying a multi-kilobyte [ItemEntity.dto] blob) only to discover
 * whether a row is a download would make every cached page a needless megabyte of I/O.
 */
data class ItemCacheKey(
    val id: UUID,
    val source: ItemSource,
    val cachedAt: Instant,
)

/**
 * One item reduced to the rows the offline read path walks *up* through — its series, season and
 * containing folder, and for a downloaded track its album and album artist (M13).
 *
 * A projection rather than [ItemEntity] because its one consumer, the delete cascade's orphan
 * prune, only needs the parent links: `SELECT *` materialised every surviving download's
 * multi-kilobyte `dto` blob once per pruned item (audit DL-05).
 *
 * [albumId] and [albumArtistId] are the M13 query columns, and they are here for exactly the reason
 * [seriesId] is: without them the prune would drop the album and artist rows of a *surviving*
 * downloaded track the moment any other download was deleted, and the offline artist → album →
 * tracks walk would dead-end at the artist page.
 */
data class ItemParentRefs(
    val id: UUID,
    val parentId: UUID?,
    val seriesId: UUID?,
    val seasonId: UUID?,
    val albumId: UUID? = null,
    val albumArtistId: UUID? = null,
)

/**
 * One downloaded row reduced to what a library-grid **filter** is decided from.
 *
 * A projection rather than whole rows because the offline grid has to filter the *whole* result set
 * before it can take a page of it — a genre filter is not expressible in the statement, so a `LIMIT`
 * there would page the unfiltered list (see [dev.jellyboost.core.database.dao.ItemDao
 * .downloadedListKeys]) — and reading every downloaded item's multi-kilobyte [ItemEntity.dto] blob
 * to answer a question about five small columns would be a needless megabyte of I/O.
 *
 * [played] and [isFavorite] come from a `LEFT JOIN` on `user_data` and are `false` when this user
 * has no row for the item, which is what "unwatched" means.
 */
data class DownloadedItemKey(
    val id: UUID,
    val genres: List<String>,
    val productionYear: Int?,
    val officialRating: String?,
    val played: Boolean,
    val isFavorite: Boolean,
)

/**
 * One downloaded row reduced to the three columns a **filter sheet** is built from.
 *
 * A projection rather than whole rows for [DownloadedItemKey]'s reason, and more sharply: the
 * facets are the distinct genres, years and ratings across *every* downloaded item, so the query
 * behind them has no `WHERE` beyond source and type and no `LIMIT` at all. Reading it as
 * [ItemEntity] deserialised every downloaded item's multi-kilobyte `dto` blob — the whole offline
 * library, in bytes — to answer a question about three small columns, every time the sheet was
 * opened (audit 2026-08-08, PERF-18).
 */
data class FacetKey(
    val genres: List<String>,
    val productionYear: Int?,
    val officialRating: String?,
)

/**
 * One downloaded row reduced to the two ids the offline *Latest* shelf needs: the row itself, and
 * the **card** it collapses into.
 *
 * Online, `getLatestMedia` groups a TV library's new episodes into their series (the server's
 * `GroupItems` behaviour), so the shelf shows one poster per show however many episodes landed.
 * Offline the same reduction is done here: [groupId] is an episode's `seriesId` and an item's own
 * id otherwise, so taking the first row of each [groupId] out of a `cachedAt DESC` list yields one
 * card per series, ordered by its most recent download.
 *
 * A projection rather than whole rows because the grouping has to look at **every** downloaded
 * item — the reduction happens before the row limit, so a series with twenty episodes must not
 * consume twenty of the sixteen slots — and reading twenty multi-kilobyte
 * [ItemEntity.dto] blobs to answer a question about two ids would be a needless megabyte of I/O.
 */
data class LatestDownloadKey(
    val id: UUID,
    val groupId: UUID,
)
