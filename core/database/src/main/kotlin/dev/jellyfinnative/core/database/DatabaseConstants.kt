package dev.jellyfinnative.core.database

/** Shared constants for the Room database defined in this module (schema landed in M4/M7). */
object DatabaseConstants {
    const val DATABASE_NAME = "jellyfin-native.db"

    /** Bumped whenever entities change; migrations live alongside `JellyfinDatabase`. */
    const val DATABASE_VERSION = 1
}
