package dev.jellyboost.core.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadStatus
import java.util.UUID

/**
 * One file belonging to a [DownloadEntity] — the media file, a subtitle track, a poster, a
 * trickplay tile sheet (docs/PLAN.md, "Download pipeline" → File plan).
 *
 * The foreign key onto `downloads` cascades on delete, which is what makes the plan's delete
 * cascade a single `DELETE FROM downloads` after the files themselves are unlinked.
 *
 * The unique index is what makes re-running the file plan idempotent: re-enqueueing an item, or
 * resuming it after a crash, matches the existing row for the same (item, type, stream, tile)
 * instead of inserting a second one and orphaning the first file on disk.
 *
 * @property id surrogate key — unlike the item table there is no natural one, since an item can
 *   have many subtitle tracks and many trickplay tiles.
 * @property streamIndex the media stream this file belongs to, for [DownloadFileType.SUBTITLE]
 *   and [DownloadFileType.AUDIO] (an extra-language sidecar names the source stream it was
 *   extracted from); `null` for every other type. The unique index below leans on it: two
 *   sidecars of one item are distinct rows only because their stream indices differ.
 * @property tileIndex which trickplay tile sheet this is; `null` for every other type.
 * @property tileWidth the trickplay resolution the tiles were requested at; the URL cannot be
 *   rebuilt without it.
 * @property path absolute filesystem path of the file. Stored as a plain string rather than a
 *   `Uri` so a future SAF storage backend can put a `content://` URI in the same column.
 * @property bytesTotal expected size, `0` until the server's `Content-Length` says otherwise.
 */
@Entity(
    tableName = "download_files",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["itemId"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["itemId", "type", "streamIndex", "tileIndex"], unique = true),
    ],
)
data class DownloadFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val itemId: UUID,
    val type: DownloadFileType,
    val streamIndex: Int? = null,
    val tileIndex: Int? = null,
    val tileWidth: Int? = null,
    val fileName: String,
    val path: String,
    val url: String,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
)

/**
 * A download together with its files — what the queue processes and what the *Downloaded* tab
 * sizes.
 *
 * Room fills [files] with a second query rather than a join, so the download row is not duplicated
 * once per file.
 */
data class DownloadWithFiles(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "itemId", entityColumn = "itemId")
    val files: List<DownloadFileEntity>,
) {
    /** Bytes this item currently occupies on disk, summed over the files actually written. */
    val bytesOnDisk: Long get() = files.sumOf { it.bytesDownloaded }
}
