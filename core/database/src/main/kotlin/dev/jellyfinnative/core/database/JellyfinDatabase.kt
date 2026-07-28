package dev.jellyfinnative.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jellyfinnative.core.database.converters.InstantConverter
import dev.jellyfinnative.core.database.converters.ItemSourceConverter
import dev.jellyfinnative.core.database.converters.ItemTypeConverter
import dev.jellyfinnative.core.database.converters.StringListConverter
import dev.jellyfinnative.core.database.converters.UuidConverter
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.dao.ServerDao
import dev.jellyfinnative.core.database.dao.UserDao
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import dev.jellyfinnative.core.database.entities.ServerAddressEntity
import dev.jellyfinnative.core.database.entities.ServerEntity
import dev.jellyfinnative.core.database.entities.UserDataEntity
import dev.jellyfinnative.core.database.entities.UserEntity

/**
 * The app's single Room database.
 *
 * Entities land incrementally per milestone (session schema at M1; `user_data` at M4; `items` +
 * `library_views` at M6; the download schema follows at M7 per docs/PLAN.md's "Data layer"
 * section — bump [DatabaseConstants.DATABASE_VERSION] and add a migration when it does).
 *
 * Schemas are exported to `core/database/schemas/`, which is what lets each version bump be an
 * `@AutoMigration` instead of hand-written SQL as long as the change is purely additive.
 */
@Database(
    entities = [
        ServerEntity::class,
        ServerAddressEntity::class,
        UserEntity::class,
        UserDataEntity::class,
        ItemEntity::class,
        LibraryViewEntity::class,
    ],
    version = DatabaseConstants.DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        // v1 → v2 adds the `user_data` table only; no existing table or column changes.
        AutoMigration(from = 1, to = 2),
        // v2 → v3 adds the `items` and `library_views` tables only; again purely additive.
        AutoMigration(from = 2, to = 3),
    ],
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
    ItemTypeConverter::class,
    ItemSourceConverter::class,
    StringListConverter::class,
)
abstract class JellyfinDatabase : RoomDatabase() {
    /** DAO for [ServerEntity] and [ServerAddressEntity]. */
    abstract fun serverDao(): ServerDao

    /** DAO for [UserEntity]. */
    abstract fun userDao(): UserDao

    /** DAO for [UserDataEntity]. */
    abstract fun userDataDao(): UserDataDao

    /** DAO for [ItemEntity] — the browse cache and the offline library (M6). */
    abstract fun itemDao(): ItemDao

    /** DAO for [LibraryViewEntity] — the cached library list (M6). */
    abstract fun libraryViewDao(): LibraryViewDao
}
