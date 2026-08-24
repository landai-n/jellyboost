package dev.jellyboost.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * Only the libraries this app supports are stored — `getUserViews` results are filtered before they reach
 * this table, so [collectionType] is the domain `CollectionKind` name.
 *
 * @property sortIndex the position the server returned this library at; the offline read replays that order
 *   rather than sorting alphabetically, so *My Media* looks the same either way.
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
