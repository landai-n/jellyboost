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
 * One downloaded (or queued) item, keyed by **item id**: one download per item, so enqueueing twice is an
 * upsert and the badge lookup is a primary-key hit.
 *
 * [bytesDownloaded] / [bytesTotal] are the *single source of truth* for progress — nothing keeps a second
 * copy in memory, which is why killing the app mid-download loses nothing.
 *
 * @property userId the delete cascade needs it to decide which `UserDataEntity` rows are safe to drop.
 * @property mediaSourceId the version that is actually on disk, which offline playback resolves against.
 * @property quality what the preference said **when the user tapped Download**, stored rather than re-read:
 *   the partial file is the resume bookmark, and a plan rebuilt at another quality appends incompatible
 *   bytes. Column default `ORIGINAL` (every pre-v5 row).
 * @property projectedBytes what the file is now *expected* to weigh, as opposed to [bytesTotal], the ceiling
 *   it is promised not to exceed. Never overwrites [bytesTotal]; `null` means "nothing better than the
 *   ceiling to say", which is permanent for an `ORIGINAL` row. Cleared when the media file finishes.
 * @property sizeIsExact `true` when [bytesTotal] is the real size rather than an upper bound — it decides
 *   between the *"X"*, *"~X"* and *"up to X"* wordings. Column default `0`: unknown means capped.
 * @property queuePosition the queue always takes the pending row with the lowest value.
 * @property attemptCount transient-failure retries since the last thing the user did to this row; the cap
 *   that keeps the retry policy bounded. *Resume* clears it, so a user-initiated attempt starts fresh.
 * @property bakedAudioStreamIndex the **absolute** `MediaStream.index` of the one audio track a transcoded
 *   download asked for; `null` for `ORIGINAL` (which holds every track) and for a downgraded row. The
 *   cached `BaseItemDto` describes the *source*, so without this offline playback falls back to
 *   `MediaSourceInfo.defaultAudioStreamIndex`, which is not always the track that made it into the file.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["queuePosition"]),
        Index(value = ["userId"]),
        // `(seriesName, quality)` is what both sibling-size lookups filter on, on the enqueue path of every
        // episode of a season; without it they fall back to a status index or a full scan.
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
    /**
     * The heading this row belongs under: an episode's series, a track's **album**, `null` for a film. The
     * name predates music and is kept because the column's meaning did not change.
     */
    val seriesName: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val progress: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

data class DownloadProgress(
    val itemId: UUID,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)
