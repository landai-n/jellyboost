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
 * @property sortName the server's `sortName`, falling back to [name]; the library grid's sort key.
 * @property cachedAt when this row was last written. Doubles as the "recently downloaded" ordering
 *   for the offline home rows, which is why a browse write-through must not bump it on a
 *   [ItemSource.DOWNLOAD] row.
 */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["source"]),
        Index(value = ["type"]),
        Index(value = ["parentId"]),
        Index(value = ["seriesId"]),
        Index(value = ["seasonId"]),
        Index(value = ["sortName"]),
        Index(value = ["cachedAt"]),
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
 * containing folder.
 *
 * A projection rather than [ItemEntity] because its one consumer, the delete cascade's orphan
 * prune, only needs the parent links: `SELECT *` materialised every surviving download's
 * multi-kilobyte `dto` blob once per pruned item (audit DL-05).
 */
data class ItemParentRefs(
    val id: UUID,
    val parentId: UUID?,
    val seriesId: UUID?,
    val seasonId: UUID?,
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
