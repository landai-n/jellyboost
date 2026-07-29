package dev.jellyfinnative.core.database

/**
 * Shared constants for the Room database defined in this module (session schema landed at M1,
 * `user_data` at M4, the item cache at M6, the download schema at M7).
 */
object DatabaseConstants {
    const val DATABASE_NAME = "jellyfin-native.db"

    /**
     * Bumped whenever entities change; migrations live alongside `JellyfinDatabase`.
     *
     * - v1 — M1 session schema (`servers`, `server_addresses`, `users`).
     * - v2 — M4 user data (`user_data`), added by `@AutoMigration(1, 2)`.
     * - v3 — M6 offline read path (`items`, `library_views`), added by `@AutoMigration(2, 3)`.
     * - v4 — M7 downloads (`downloads`, `download_files`), added by `@AutoMigration(3, 4)`.
     * - v5 — M9 download quality (`downloads.quality`, default `ORIGINAL`), by `@AutoMigration(4, 5)`.
     * - v6 — live transcode size projection (`downloads.projectedBytes` nullable,
     *   `downloads.sizeIsExact` default `0`), by `@AutoMigration(5, 6)`.
     */
    const val DATABASE_VERSION = 6
}
