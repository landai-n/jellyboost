package dev.jellyfinnative.core.database.converters

import androidx.room.TypeConverter
import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.DownloadStatus

/**
 * Room converters for the enum columns of the M7 download schema.
 *
 * Same rule as [ItemTypeConverter]: enums are persisted by **name**, and an unrecognised stored
 * name decodes to a safe default rather than throwing, so a row written by a newer build can never
 * crash an older one on read.
 */
class DownloadStatusConverter {
    /** Reads a stored status, defaulting to [DownloadStatus.ERROR] for anything unrecognised. */
    @TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? =
        value?.let { name -> DownloadStatus.entries.firstOrNull { it.name == name } ?: DownloadStatus.ERROR }

    /** Writes a status as its constant name. */
    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? = status?.name
}

/** Room converter pair for [DownloadQuality] — what a download row was fetched at (M9). */
class DownloadQualityConverter {
    /**
     * Reads a stored quality.
     *
     * An unknown name decodes to [DownloadQuality.ORIGINAL], which is both the column's SQL default
     * and the only value every build of this app has ever understood.
     */
    @TypeConverter
    fun toDownloadQuality(value: String?): DownloadQuality? = value?.let(DownloadQuality::fromNameOrDefault)

    /** Writes a quality as its constant name. */
    @TypeConverter
    fun fromDownloadQuality(quality: DownloadQuality?): String? = quality?.name
}

/** Room converter pair for [DownloadFileType]. */
class DownloadFileTypeConverter {
    /**
     * Reads a stored file type.
     *
     * An unknown name decodes to [DownloadFileType.TRICKPLAY_TILE] — the *least* essential kind —
     * so a file this build does not understand can never make an otherwise-complete item look
     * broken.
     */
    @TypeConverter
    fun toDownloadFileType(value: String?): DownloadFileType? =
        value?.let { name ->
            DownloadFileType.entries.firstOrNull { it.name == name } ?: DownloadFileType.TRICKPLAY_TILE
        }

    /** Writes a file type as its constant name. */
    @TypeConverter
    fun fromDownloadFileType(type: DownloadFileType?): String? = type?.name
}
