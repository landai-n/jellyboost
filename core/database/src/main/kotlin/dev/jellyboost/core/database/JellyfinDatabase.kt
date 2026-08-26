package dev.jellyboost.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jellyboost.core.database.converters.DownloadFileTypeConverter
import dev.jellyboost.core.database.converters.DownloadQualityConverter
import dev.jellyboost.core.database.converters.DownloadStatusConverter
import dev.jellyboost.core.database.converters.InstantConverter
import dev.jellyboost.core.database.converters.ItemSourceConverter
import dev.jellyboost.core.database.converters.ItemTypeConverter
import dev.jellyboost.core.database.converters.StringListConverter
import dev.jellyboost.core.database.converters.UuidConverter
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.database.entities.ServerAddressEntity
import dev.jellyboost.core.database.entities.ServerEntity
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.database.entities.UserEntity

/**
 * The app's single Room database.
 *
 * Schemas are exported to `core/database/schemas/`, which is what lets each version bump stay an
 * `@AutoMigration`: Room can derive one only while no **column** is dropped, renamed or retyped and every
 * new `NOT NULL` column brings a SQL default. Break that and an existing install loses its cached items,
 * its pending user-data rows and its download queue.
 */
@Database(
    entities = [
        ServerEntity::class,
        ServerAddressEntity::class,
        UserEntity::class,
        UserDataEntity::class,
        ItemEntity::class,
        LibraryViewEntity::class,
        DownloadEntity::class,
        DownloadFileEntity::class,
    ],
    version = DatabaseConstants.DATABASE_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
    ],
)
@TypeConverters(
    UuidConverter::class,
    InstantConverter::class,
    ItemTypeConverter::class,
    ItemSourceConverter::class,
    StringListConverter::class,
    DownloadStatusConverter::class,
    DownloadFileTypeConverter::class,
    DownloadQualityConverter::class,
)
abstract class JellyfinDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun userDao(): UserDao

    abstract fun userDataDao(): UserDataDao

    abstract fun itemDao(): ItemDao

    abstract fun libraryViewDao(): LibraryViewDao

    abstract fun downloadDao(): DownloadDao
}
