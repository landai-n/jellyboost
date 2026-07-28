package dev.jellyfinnative.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jellyfinnative.core.database.converters.UuidConverter
import dev.jellyfinnative.core.database.dao.ServerDao
import dev.jellyfinnative.core.database.dao.UserDao
import dev.jellyfinnative.core.database.entities.ServerAddressEntity
import dev.jellyfinnative.core.database.entities.ServerEntity
import dev.jellyfinnative.core.database.entities.UserEntity

/**
 * The app's single Room database.
 *
 * Entities land incrementally per milestone (session schema at M1; item/download/user-data
 * schema follow at later milestones per docs/PLAN.md's "Data layer" section — bump
 * [DatabaseConstants.DATABASE_VERSION] and add a migration when they do).
 */
@Database(
    entities = [ServerEntity::class, ServerAddressEntity::class, UserEntity::class],
    version = DatabaseConstants.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(UuidConverter::class)
abstract class JellyfinDatabase : RoomDatabase() {
    /** DAO for [ServerEntity] and [ServerAddressEntity]. */
    abstract fun serverDao(): ServerDao

    /** DAO for [UserEntity]. */
    abstract fun userDao(): UserDao
}
