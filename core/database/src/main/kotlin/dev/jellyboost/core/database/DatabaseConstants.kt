package dev.jellyboost.core.database

/** Shared constants for the Room database defined in this module. */
object DatabaseConstants {
    const val DATABASE_NAME = "jellyboost.db"

    /**
     * Bumped whenever entities change; migrations live alongside `JellyfinDatabase`.
     *
     * - v1 — session schema (`servers`, `server_addresses`, `users`).
     * - v2 — user data (`user_data`), added by `@AutoMigration(1, 2)`.
     * - v3 — offline read path (`items`, `library_views`), added by `@AutoMigration(2, 3)`.
     * - v4 — downloads (`downloads`, `download_files`), added by `@AutoMigration(3, 4)`.
     * - v5 — download quality (`downloads.quality`, default `ORIGINAL`), by `@AutoMigration(4, 5)`.
     * - v6 — live transcode size projection (`downloads.projectedBytes` nullable,
     *   `downloads.sizeIsExact` default `0`), by `@AutoMigration(5, 6)`.
     * - v7 — bounded retry of transient download failures (`downloads.attemptCount`, default `0`),
     *   by `@AutoMigration(6, 7)`.
     * - v8 — the audio track a transcode baked in (`downloads.bakedAudioStreamIndex`, nullable),
     *   by `@AutoMigration(7, 8)`.
     * - v9 — **indices only**, no column or table change: `items` gains `(source, type)` and
     *   `(source, cachedAt)` and loses the three single-column indices those subsume or never
     *   served (`source`, `cachedAt`, `sortName`); `downloads` gains `(seriesName, quality)`. By
     *   `@AutoMigration(8, 9)` — see `ItemEntity` for the query plans.
     * - v10 — music query columns (`items.albumId`, `items.albumArtistId`, both nullable and
     *   indexed), by `@AutoMigration(9, 10)`.
     */
    const val DATABASE_VERSION = 10
}
