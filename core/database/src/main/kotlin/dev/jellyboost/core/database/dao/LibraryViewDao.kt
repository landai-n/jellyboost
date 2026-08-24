package dev.jellyboost.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jellyboost.core.database.entities.LibraryViewEntity
import java.util.UUID

@Dao
interface LibraryViewDao {
    @Upsert
    suspend fun upsert(views: List<LibraryViewEntity>)

    @Query("SELECT * FROM library_views ORDER BY sortIndex ASC")
    suspend fun getAll(): List<LibraryViewEntity>

    /**
     * Called with the ids of a *successful* `getUserViews`. An empty [keepIds] would wipe the table, which is
     * why the write-through skips this call for an empty result.
     */
    @Query("DELETE FROM library_views WHERE id NOT IN (:keepIds)")
    suspend fun deleteExcept(keepIds: List<UUID>)
}
