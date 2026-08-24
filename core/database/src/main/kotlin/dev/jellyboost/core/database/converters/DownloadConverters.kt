package dev.jellyboost.core.database.converters

import androidx.room.TypeConverter
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus

/**
 * Same rule as [ItemTypeConverter]: enums are persisted by **name**, and an unrecognised stored name decodes
 * to a safe default rather than throwing, so a row written by a newer build cannot crash an older one.
 */
class DownloadStatusConverter {
    /** Defaults to [DownloadStatus.ERROR]. */
    @TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? =
        value?.let { name -> DownloadStatus.entries.firstOrNull { it.name == name } ?: DownloadStatus.ERROR }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? = status?.name
}

class DownloadQualityConverter {
    /** An unknown name decodes to [DownloadQuality.ORIGINAL], the column's SQL default. */
    @TypeConverter
    fun toDownloadQuality(value: String?): DownloadQuality? = value?.let(DownloadQuality::fromNameOrDefault)

    @TypeConverter
    fun fromDownloadQuality(quality: DownloadQuality?): String? = quality?.name
}

class DownloadFileTypeConverter {
    /**
     * An unknown name decodes to [DownloadFileType.TRICKPLAY_TILE] — the *least* essential kind — so a file
     * this build does not understand can never make an otherwise-complete item look broken.
     */
    @TypeConverter
    fun toDownloadFileType(value: String?): DownloadFileType? =
        value?.let { name ->
            DownloadFileType.entries.firstOrNull { it.name == name } ?: DownloadFileType.TRICKPLAY_TILE
        }

    @TypeConverter
    fun fromDownloadFileType(type: DownloadFileType?): String? = type?.name
}
