package dev.jellyfinnative.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import java.util.UUID

/** Data access for [LibraryViewEntity] — the cached *My Media* / Libraries list. */
@Dao
interface LibraryViewDao {
    /** Inserts or updates the cached libraries. */
    @Upsert
    suspend fun upsert(views: List<LibraryViewEntity>)

    /** The cached libraries in the order the server returned them. */
    @Query("SELECT * FROM library_views ORDER BY sortIndex ASC")
    suspend fun getAll(): List<LibraryViewEntity>

    /**
     * Removes libraries the server no longer reports.
     *
     * Called with the ids of a *successful* `getUserViews`, so a library the user deleted (or lost
     * access to) does not linger in the offline list forever. An empty [keepIds] would wipe the
     * table, which is why the write-through skips this call for an empty result.
     */
    @Query("DELETE FROM library_views WHERE id NOT IN (:keepIds)")
    suspend fun deleteExcept(keepIds: List<UUID>)
}
