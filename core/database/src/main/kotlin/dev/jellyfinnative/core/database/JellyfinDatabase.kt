package dev.jellyfinnative.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jellyfinnative.core.database.converters.DownloadFileTypeConverter
import dev.jellyfinnative.core.database.converters.DownloadQualityConverter
import dev.jellyfinnative.core.database.converters.DownloadStatusConverter
import dev.jellyfinnative.core.database.converters.InstantConverter
import dev.jellyfinnative.core.database.converters.ItemSourceConverter
import dev.jellyfinnative.core.database.converters.ItemTypeConverter
import dev.jellyfinnative.core.database.converters.StringListConverter
import dev.jellyfinnative.core.database.converters.UuidConverter
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.dao.ServerDao
import dev.jellyfinnative.core.database.dao.UserDao
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import dev.jellyfinnative.core.database.entities.ServerAddressEntity
import dev.jellyfinnative.core.database.entities.ServerEntity
import dev.jellyfinnative.core.database.entities.UserDataEntity
import dev.jellyfinnative.core.database.entities.UserEntity

/**
 * The app's single Room database.
 *
 * Entities land incrementally per milestone: session schema at M1; `user_data` at M4; `items` +
 * `library_views` at M6; `downloads` + `download_files` at M7.
 *
 * Schemas are exported to `core/database/schemas/`, which is what lets each version bump be an
 * `@AutoMigration` instead of hand-written SQL as long as the change is purely additive. Every
 * version so far has been — v4 only adds two tables and v5 one column with a SQL default, so an
 * existing install keeps its cached items, its pending user-data rows and its download queue across
 * every upgrade.
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
        // v1 → v2 adds the `user_data` table only; no existing table or column changes.
        AutoMigration(from = 1, to = 2),
        // v2 → v3 adds the `items` and `library_views` tables only; again purely additive.
        AutoMigration(from = 2, to = 3),
        // v3 → v4 adds `downloads` and `download_files`; nothing existing is touched.
        AutoMigration(from = 3, to = 4),
        // v4 → v5 adds `downloads.quality`, a NOT NULL column with the SQL default `ORIGINAL`, so
        // every row an older build wrote reads back as the behaviour that build had (M9).
        AutoMigration(from = 4, to = 5),
        // v5 → v6 adds `downloads.projectedBytes` (nullable, so it needs no default) and
        // `downloads.sizeIsExact` (NOT NULL, SQL default `0`). Both are additive, and both read
        // back on an older row as "no projection, size is a ceiling" — what that row always meant.
        AutoMigration(from = 5, to = 6),
        // v6 → v7 adds `downloads.attemptCount` (NOT NULL, SQL default `0`). An older row reads
        // back as "nothing has failed on this yet", which is the only honest reading of a build
        // that had no retry policy at all.
        AutoMigration(from = 6, to = 7),
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

    /** DAO for [DownloadEntity] and [DownloadFileEntity] — the download pipeline (M7). */
    abstract fun downloadDao(): DownloadDao
}
