package dev.jellyboost.core.database.converters

import androidx.room.TypeConverter
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.entities.ItemSource

/**
 * Enums are persisted by **name**, not by ordinal: DAO queries filter these columns with readable literals,
 * and an ordinal would silently re-map every stored row the day a constant is inserted mid-enum. An unknown
 * stored name decodes to the enum's catch-all — a row written by a newer build must never crash an older one.
 */
class ItemTypeConverter {
    @TypeConverter
    fun toItemType(value: String?): ItemType? =
        value?.let { name -> ItemType.entries.firstOrNull { it.name == name } ?: ItemType.UNKNOWN }

    @TypeConverter
    fun fromItemType(type: ItemType?): String? = type?.name
}

class ItemSourceConverter {
    /** Defaults to [ItemSource.BROWSE_CACHE], the evictable kind. */
    @TypeConverter
    fun toItemSource(value: String?): ItemSource? =
        value?.let { name -> ItemSource.entries.firstOrNull { it.name == name } ?: ItemSource.BROWSE_CACHE }

    @TypeConverter
    fun fromItemSource(source: ItemSource?): String? = source?.name
}

/**
 * Genres are newline-joined rather than JSON: they are only ever read back whole, and genre names cannot
 * contain a newline.
 */
class StringListConverter {
    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.let { if (it.isEmpty()) emptyList() else it.split(SEPARATOR) }

    @TypeConverter
    fun fromStringList(values: List<String>?): String? = values?.joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\n"
    }
}
