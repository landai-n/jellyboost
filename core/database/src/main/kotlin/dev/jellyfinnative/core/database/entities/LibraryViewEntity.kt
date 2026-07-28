package dev.jellyfinnative.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * One of the user's libraries ("Films", "Séries"), cached so that the home screen's *My Media* row
 * and the Libraries tab still render with no server (docs/PLAN.md, ":core:database").
 *
 * Only the libraries v1 supports are stored: `getUserViews` results are filtered to movies and TV
 * before they ever reach this table, so [collectionType] is the domain `CollectionKind` name.
 *
 * @property sortIndex the position the server returned this library at; the offline read replays
 *   that order rather than sorting alphabetically, so *My Media* looks the same either way.
 */
@Entity(tableName = "library_views")
data class LibraryViewEntity(
    @PrimaryKey
    val id: UUID,
    val name: String,
    val collectionType: String,
    val sortIndex: Int,
    val cachedAt: Instant,
    val primaryImageTag: String? = null,
    val thumbImageTag: String? = null,
)
