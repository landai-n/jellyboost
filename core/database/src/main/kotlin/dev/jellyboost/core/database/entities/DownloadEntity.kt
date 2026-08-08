package dev.jellyboost.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import java.time.Instant
import java.util.UUID

/**
 * One downloaded (or queued) item.
 *
 * The primary key is the **item id** ([D] in docs/PLAN.md): one download per item, so enqueueing
 * something twice is an upsert rather than a duplicate, and the badge lookup is a primary-key hit.
 *
 * ### Room is the progress
 * [bytesDownloaded] / [bytesTotal] are the *single source of truth* for progress (docs/PLAN.md,
 * "Download pipeline" → Progress). `FileDownloader` reports every 64 KB and the writer throttles to
 * a row write every 500 ms or 1 %, and everything downstream — the queue tab, the app-wide
 * `DownloadBadge`, the foreground notification — is a Room Flow over this column. Nothing keeps a
 * second copy in memory, which is why killing the app mid-download loses nothing.
 *
 * ### Why the metadata columns
 * [itemName] / [seriesName] / [directoryName] are denormalised copies of what the matching
 * [ItemEntity] holds. The queue has to render a row (and the notification a title) for an item
 * whose `items` row may not be written yet, and the delete cascade has to know which directory to
 * remove **after** the item row is gone. Both are read without decoding a multi-kilobyte
 * `BaseItemDto` blob.
 *
 * @property userId owner of the download; the delete cascade needs it to decide which
 *   `UserDataEntity` rows are safe to drop.
 * @property mediaSourceId the media source the file plan was built from — the version that is
 *   actually on disk, which offline playback (M8) resolves against.
 * @property quality what the user's *download quality* preference said **when they tapped
 *   Download** (M9). It is stored rather than re-read because the file on disk was fetched at this
 *   quality and the partial file is the resume bookmark: a plan rebuilt at a different quality
 *   mid-transfer would append incompatible bytes (DECISIONS.md, 2026-07-29). Column default
 *   `ORIGINAL`, which is what every row written before schema v5 was.
 * @property projectedBytes what the finished file is now *expected* to weigh, as opposed to
 *   [bytesTotal], which is the ceiling it is promised not to exceed (schema v6). `null` means
 *   "nothing better than the ceiling to say", which is the permanent state of an `ORIGINAL`
 *   download (its total is already exact) and the opening state of a transcoded one. It is filled
 *   from two independent sources and never overwrites [bytesTotal]: at enqueue, from finished
 *   episodes of the same series at the same quality (`DownloadEnqueuer`), and in flight, from the
 *   media time the arriving Matroska stream has delivered (`TranscodeSizeProjector`). Cleared when
 *   the media file finishes, because at that point the real size is simply known.
 * @property sizeIsExact `true` when [bytesTotal] is the size the file will actually be rather than
 *   an upper bound (schema v6) — the server reported it (`ORIGINAL`), or the transcode request will
 *   be answered with a video stream copy, which is predictable to within the audio track. It is
 *   what decides between the Downloads screen's *"X"*, *"~X"* and *"up to X"* wordings. Column
 *   default `0`, which is the honest reading of every row written before v6: unknown means capped.
 * @property queuePosition ordering key for the queue tab; the queue always takes the pending row
 *   with the lowest value. Reordering rewrites this column and nothing else.
 * @property attemptCount how many times a *transient* failure has put this row back in the queue
 *   since the last thing the user did to it (schema v7). It is the cap that keeps the retry policy
 *   bounded: past it the row goes [DownloadStatus.ERROR] rather than being retried forever
 *   (docs/notes/audit-2026-07.md, STAB-01). Enqueue writes a whole row, and *Resume* clears the
 *   column, so a user-initiated attempt always starts from a full budget. Column default `0`, which
 *   is what every row written before v7 means: nothing has failed on it yet.
 * @property bakedAudioStreamIndex the **absolute** `MediaStream.index` of the one audio track a
 *   transcoded download asked the server to encode (schema v8). `null` for an `ORIGINAL` row, which
 *   holds every audio track of the source and needs no pin; `null` too for a row the
 *   transcode-fallback downgraded to `ORIGINAL` before it was written, and for a transcoded item
 *   with no audio streams at all. It exists because the cached `BaseItemDto` describes the *source*:
 *   without it, offline playback can only guess which of the source's tracks survived into the file,
 *   and the guess it used to make (`MediaSourceInfo.defaultAudioStreamIndex`) is exactly the
 *   assumption the M10 track fix flagged. Rows written before v8 read back `NULL` and keep that
 *   fallback.
 * @property errorMessage last failure, kept so the queue tab can say *why* an item is in
 *   [DownloadStatus.ERROR] instead of just that it is.
 * @property createdAt when the item was enqueued; the *Downloaded* tab's default ordering.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["queuePosition"]),
        Index(value = ["userId"]),
        // `(seriesName, quality)` is the pair both sibling-size lookups filter on
        // (`completedSiblings`, `unseededSiblings`), and they run on the enqueue path for every
        // episode of a season. Without it `completedSiblings` fell back to `index_downloads_status`
        // and `unseededSiblings` scanned the table in queue order (schema v9, audit PERF-23).
        Index(value = ["seriesName", "quality"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey
    val itemId: UUID,
    val userId: UUID,
    val status: DownloadStatus,
    val mediaSourceId: String? = null,
    @ColumnInfo(defaultValue = "ORIGINAL")
    val quality: DownloadQuality = DownloadQuality.ORIGINAL,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val projectedBytes: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val sizeIsExact: Boolean = false,
    val queuePosition: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    val bakedAudioStreamIndex: Int? = null,
    /** Directory name under the storage root, e.g. `Westworld - S01E02 - Chestnut`. */
    val directoryName: String,
    val itemName: String,
    val seriesName: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Transfer progress in `0f..1f`, or `0f` while the total size is still unknown. */
    val progress: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Projection of just the columns a badge needs.
 *
 * Every card in the app subscribes to this (docs/PLAN.md: "Every item card shows `DownloadBadge`
 * from `DownloadDao.observeStatusMap()`"), so the query that feeds them must not drag whole rows —
 * let alone the joined `items` blobs — through Room on every 500 ms progress write.
 */
data class DownloadProgress(
    val itemId: UUID,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)
