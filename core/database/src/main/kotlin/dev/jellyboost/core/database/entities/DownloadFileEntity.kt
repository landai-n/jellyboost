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
 * One file belonging to a [DownloadEntity] — the media file, a subtitle track, a poster, a trickplay sheet.
 *
 * The unique index is what makes re-running the file plan idempotent: re-enqueueing an item, or resuming it
 * after a crash, matches the existing row for the same (item, type, stream, tile) instead of inserting a
 * second one and orphaning the first file on disk.
 *
 * @property streamIndex the media stream this file belongs to, for [DownloadFileType.SUBTITLE] and
 *   [DownloadFileType.AUDIO]; `null` otherwise. The unique index leans on it — two sidecars of one item are
 *   distinct rows only because their stream indices differ.
 * @property tileWidth the trickplay resolution the tiles were requested at; the URL cannot be rebuilt without it.
 * @property path stored as a plain string rather than a `Uri` so a future SAF backend can put a `content://`
 *   URI in the same column.
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

data class DownloadWithFiles(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "itemId", entityColumn = "itemId")
    val files: List<DownloadFileEntity>,
) {
    val bytesOnDisk: Long get() = files.sumOf { it.bytesDownloaded }
}
