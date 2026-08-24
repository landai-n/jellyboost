package dev.jellyboost.core.database.converters

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Epoch milliseconds rather than ISO-8601 text: the user-data sync compares timestamps *in SQL*
 * (`updatedAt <= :syncedAt`, most-recent-wins), and `Instant.toString()` is not lexicographically ordered —
 * `…T10:00:00.500Z` sorts *before* `…T10:00:00Z` because `'.' < 'Z'`. Integers compare correctly and index better.
 */
class InstantConverter {
    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()
}
