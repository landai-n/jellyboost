package dev.jellyfinnative.core.database.converters

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room [androidx.room.TypeConverter] pair for [Instant] columns.
 *
 * Instants are stored as epoch milliseconds rather than ISO-8601 text on purpose: the user-data
 * sync path compares timestamps *in SQL* (`updatedAt <= :syncedAt`, and most-recent-wins in M8),
 * and `Instant.toString()` is not lexicographically ordered — `…T10:00:00.500Z` sorts *before*
 * `…T10:00:00Z` because `'.' < 'Z'`. Integers compare correctly and index better.
 */
class InstantConverter {
    /** Converts stored epoch milliseconds back into an [Instant], or `null` if [value] is `null`. */
    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    /** Converts an [Instant] into epoch milliseconds for storage, or `null`. */
    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()
}
