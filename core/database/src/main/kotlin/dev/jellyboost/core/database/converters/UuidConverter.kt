package dev.jellyboost.core.database.converters

import androidx.room.TypeConverter
import java.util.UUID

/** Entities key on [UUID] because the SDK models do; Room persists it as its canonical string representation. */
class UuidConverter {
    @TypeConverter
    fun toUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun fromUuid(uuid: UUID?): String? = uuid?.toString()
}
