package dev.jellyfinnative.core.database

/**
 * Shared constants for the Room database defined in this module (session schema landed at M1,
 * `user_data` at M4).
 */
object DatabaseConstants {
    const val DATABASE_NAME = "jellyfin-native.db"

    /**
     * Bumped whenever entities change; migrations live alongside `JellyfinDatabase`.
     *
     * - v1 — M1 session schema (`servers`, `server_addresses`, `users`).
     * - v2 — M4 user data (`user_data`), added by `@AutoMigration(1, 2)`.
     */
    const val DATABASE_VERSION = 2
}
