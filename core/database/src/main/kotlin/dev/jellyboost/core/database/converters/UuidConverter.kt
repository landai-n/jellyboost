package dev.jellyboost.core.database.converters

import androidx.room.TypeConverter
import java.util.UUID

/**
 * Room [androidx.room.TypeConverter] pair for [UUID] columns.
 *
 * Jellyfin SDK models identify servers, users, and items with [UUID], so entities in this
 * module use [UUID] as their natural primary-key type; Room persists it as its canonical
 * string representation.
 */
class UuidConverter {
    /** Converts a stored [UUID] string back into a [UUID], or `null` if [value] is `null`. */
    @TypeConverter
    fun toUuid(value: String?): UUID? = value?.let(UUID::fromString)

    /** Converts a [UUID] into its canonical string representation for storage, or `null`. */
    @TypeConverter
    fun fromUuid(uuid: UUID?): String? = uuid?.toString()
}
