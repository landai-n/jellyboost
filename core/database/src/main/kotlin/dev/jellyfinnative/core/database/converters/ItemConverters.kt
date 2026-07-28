package dev.jellyfinnative.core.database.converters

import androidx.room.TypeConverter
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.entities.ItemSource

/**
 * Room converters for the enum columns of
 * [dev.jellyfinnative.core.database.entities.ItemEntity].
 *
 * Enums are persisted by **name**, not by ordinal: DAO queries filter on these columns with
 * readable literals, and an ordinal would silently re-map every stored row the day a constant is
 * inserted in the middle of the enum.
 *
 * An unknown stored name decodes to the enum's catch-all rather than throwing — a row written by a
 * newer build must never be able to crash an older one on read.
 */
class ItemTypeConverter {
    /** Reads a stored item type, falling back to [ItemType.UNKNOWN] for anything unrecognised. */
    @TypeConverter
    fun toItemType(value: String?): ItemType? =
        value?.let { name -> ItemType.entries.firstOrNull { it.name == name } ?: ItemType.UNKNOWN }

    /** Writes an item type as its constant name. */
    @TypeConverter
    fun fromItemType(type: ItemType?): String? = type?.name
}

/** Room converter pair for [ItemSource]. */
class ItemSourceConverter {
    /** Reads a stored source, defaulting to [ItemSource.BROWSE_CACHE] (the evictable kind). */
    @TypeConverter
    fun toItemSource(value: String?): ItemSource? =
        value?.let { name -> ItemSource.entries.firstOrNull { it.name == name } ?: ItemSource.BROWSE_CACHE }

    /** Writes a source as its constant name. */
    @TypeConverter
    fun fromItemSource(source: ItemSource?): String? = source?.name
}

/**
 * Room converter pair for the `genres` column.
 *
 * Genres are stored as a newline-joined string rather than JSON: they are only ever read back
 * whole (to rebuild the domain item's list and to compute the offline filter facets), and a plain
 * separator keeps the column greppable in `adb shell sqlite3` during device verification. Genre
 * names cannot contain a newline.
 */
class StringListConverter {
    /** Splits a stored list back into its elements; a blank column decodes to an empty list. */
    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.let { if (it.isEmpty()) emptyList() else it.split(SEPARATOR) }

    /** Joins a list for storage. */
    @TypeConverter
    fun fromStringList(values: List<String>?): String? = values?.joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\n"
    }
}
