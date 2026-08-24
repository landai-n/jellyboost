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
 * Entities land incrementally as features need them: session schema, `user_data`, `items` +
 * `library_views`, then `downloads` + `download_files`.
 *
 * Schemas are exported to `core/database/schemas/`, which is what lets each version bump be an
 * `@AutoMigration` instead of hand-written SQL: Room derives the migration from the two exported
 * schemas, and can do so as long as no **column** was dropped, renamed or retyped and every new
 * `NOT NULL` column brings a SQL default. Every version so far has held that — v4 only adds two
 * tables, v5 one column with a SQL default, and v9 only rearranges indices — so an existing install
 * keeps its cached items, its pending user-data rows and its download queue across every upgrade.
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
        // every row an older build wrote reads back as the behaviour that build had.
        AutoMigration(from = 4, to = 5),
        // v5 → v6 adds `downloads.projectedBytes` (nullable, so it needs no default) and
        // `downloads.sizeIsExact` (NOT NULL, SQL default `0`). Both are additive, and both read
        // back on an older row as "no projection, size is a ceiling" — what that row always meant.
        AutoMigration(from = 5, to = 6),
        // v6 → v7 adds `downloads.attemptCount` (NOT NULL, SQL default `0`). An older row reads
        // back as "nothing has failed on this yet", which is the only honest reading of a build
        // that had no retry policy at all.
        AutoMigration(from = 6, to = 7),
        // v7 → v8 adds `downloads.bakedAudioStreamIndex` (nullable, so it needs no default). An
        // older transcoded row reads back as NULL, which is exactly what it always meant: nothing
        // recorded which audio track the server picked, so playback falls back to assuming the
        // source's `DefaultAudioStreamIndex` — the behaviour that build had.
        AutoMigration(from = 7, to = 8),
        // v8 → v9 changes **indices only** — no column, no table, no type. Room derives index
        // work from the exported schemas the same way it derives `ALTER TABLE ADD COLUMN`, so
        // this stays automatic: it drops the three `items` indices that measured as dead or
        // subsumed and creates the two composites plus `downloads (seriesName, quality)`. Rows are
        // untouched, so the migration is a rebuild of B-trees the queries were never using
        // (see `ItemEntity` for the query plans).
        AutoMigration(from = 8, to = 9),
        // v9 → v10 adds `items.albumId` and `items.albumArtistId` (both nullable, so neither needs
        // a default) plus their indexes — the query-only columns offline album/artist browsing
        // reads. An existing cached row reads back as NULL on both, which is honest: nothing wrote
        // them before this build existed, and the row's `dto` blob is unaffected either way.
        AutoMigration(from = 9, to = 10),
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

    /** DAO for [ItemEntity] — the browse cache and the offline library. */
    abstract fun itemDao(): ItemDao

    /** DAO for [LibraryViewEntity] — the cached library list. */
    abstract fun libraryViewDao(): LibraryViewDao

    /** DAO for [DownloadEntity] and [DownloadFileEntity] — the download pipeline. */
    abstract fun downloadDao(): DownloadDao
}
