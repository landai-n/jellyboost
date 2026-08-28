package dev.jellyboost.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.jellyboost.core.common.model.ItemType
import java.time.Instant
import java.util.UUID

enum class ItemSource {
    BROWSE_CACHE,

    /**
     * The item is downloaded, or is the series/season parent of a downloaded episode. **Never evicted, and
     * never downgraded to [BROWSE_CACHE]** by a later browse — losing this row would orphan the files on disk.
     */
    DOWNLOAD,
}

/**
 * One media item, cached locally.
 *
 * Image *tags* rather than image URLs are stored: a URL embeds the server's base address, which changes
 * when the reachability probe rotates to another `ServerAddressEntity`.
 *
 * Index choices (schema v9), verified with `EXPLAIN QUERY PLAN` on a 20k-row copy — every list query leads
 * with `source`, so the single-column `source`/`cachedAt` indices were dropped as redundant with the two
 * composites. `type` stays single because a composite cannot answer a type-only predicate and it has no
 * covering prefix. **There is deliberately no `sortName` index:** Room's `@Index` cannot express a
 * collation, so a `BINARY` one is never used by the `COLLATE NOCASE` sorts — pure write amplification.
 *
 * @property cachedAt doubles as the "recently downloaded" ordering for the offline home rows, which is why
 *   a browse write-through must not bump it on a [ItemSource.DOWNLOAD] row. It is therefore **not** a
 *   freshness key — see [revisedAt], which exists because it is not.
 * @property revisedAt when this row was last written, stamped by every upsert path. Separate from
 *   [cachedAt] precisely because two writers deliberately preserve that one across an in-place rewrite
 *   (`DownloadedMetadataRefresher.store` and `BrowseCacheWriter.mergeRows`), so a memo keyed on it can
 *   never see a replaced blob. Anything caching a decoded [dto] keys on this instead.
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
    /**
     * `NOT NULL` with a SQL default of `0` (the epoch), which is what keeps the v12 → v13 bump an
     * `@AutoMigration`. That default is also the honest reading of a row written before the column: its
     * blob was last replaced at some unrecorded time, and any real write is after 1970, so the first
     * rewrite invalidates every memo that was holding it.
     */
    @ColumnInfo(defaultValue = "0")
    val revisedAt: Instant = Instant.EPOCH,
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
    /** Query-only, like [albumArtistId]: a domain item still rebuilds from [dto], never from these columns. */
    val albumId: UUID? = null,
    /** The id of a track's album's artist, or an album's own artist. Query-only, see [albumId]. */
    val albumArtistId: UUID? = null,
    /** The complete `BaseItemDto` as JSON — the only thing a domain item is rebuilt from. */
    val dto: String,
)

/**
 * @property cachedAt the recency the write-through must preserve on a download row.
 * @property revisedAt the freshness key a metadata memo compares; see [ItemEntity.revisedAt].
 */
data class ItemCacheKey(
    val id: UUID,
    val source: ItemSource,
    val cachedAt: Instant,
    val revisedAt: Instant = Instant.EPOCH,
)

/**
 * [albumId] and [albumArtistId] are here for the reason [seriesId] is: without them the orphan prune drops
 * the album and artist rows of a *surviving* downloaded track as soon as any other download is deleted, and
 * the offline artist → album → tracks walk dead-ends at the artist page.
 */
data class ItemParentRefs(
    val id: UUID,
    val parentId: UUID?,
    val seriesId: UUID?,
    val seasonId: UUID?,
    val albumId: UUID? = null,
    val albumArtistId: UUID? = null,
)

data class DownloadedItemKey(
    val id: UUID,
    val genres: List<String>,
    val productionYear: Int?,
    val officialRating: String?,
    val played: Boolean,
    val isFavorite: Boolean,
)

data class FacetKey(
    val genres: List<String>,
    val productionYear: Int?,
    val officialRating: String?,
)

/**
 * [groupId] is an episode's `seriesId` and an item's own id otherwise, mirroring the server's `GroupItems`
 * behaviour: taking the first row of each [groupId] out of a `cachedAt DESC` list yields one card per series.
 * The reduction happens before the row limit, so a twenty-episode series must not consume twenty slots.
 */
data class LatestDownloadKey(
    val id: UUID,
    val groupId: UUID,
)
