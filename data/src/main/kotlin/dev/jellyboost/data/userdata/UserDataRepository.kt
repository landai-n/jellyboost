package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.UserData
import kotlinx.coroutines.flow.SharedFlow

/**
 * **Local-first, always**: every operation writes Room with `toBeSynced = true` and publishes on
 * [changes] *before* the server is contacted, so the returned [AppResult] describes the **local**
 * write. A [AppResult.Success] means the change is durable and visible, not that the server has
 * heard about it.
 */
interface UserDataRepository {
    /**
     * The **only** observation surface offered. A per-item `observe(itemId)` Room `Flow` is
     * deliberately absent: a rendered screen wants the *delta*, not a subscription re-reading the
     * table on every write.
     */
    val changes: SharedFlow<UserDataChange>

    /**
     * Marking watched also clears the resume position and stamps `lastPlayedDate`, matching the
     * server — otherwise the item returns with a stale progress bar after the next sync.
     */
    suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): AppResult<UserData>

    suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): AppResult<UserData>

    /** In Jellyfin ticks. Called by the player on every progress tick. */
    suspend fun setPosition(
        itemId: String,
        positionTicks: Long,
    ): AppResult<UserData>
}
