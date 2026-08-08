package dev.jellyboost.data.userdata

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.UserData
import kotlinx.coroutines.flow.SharedFlow

/**
 * Watched / favourite / resume-position writes.
 *
 * **Local-first, always** (docs/PLAN.md, "Data layer"): every operation writes Room with
 * `toBeSynced = true` and publishes on [changes] *before* the server is contacted. The push that
 * follows is best effort — when it fails the flag stays set and `UserDataSyncWorker` is enqueued,
 * and the UI is none the wiser. That is what makes marking something watched feel instant, and
 * what makes it work at all with no network.
 *
 * Consequently the returned [AppResult] describes the **local** write, not the network one:
 * a [AppResult.Success] means the change is durably recorded and visible, whether or not the
 * server has heard about it yet.
 */
interface UserDataRepository {
    /**
     * Local user-data changes, for list ViewModels to patch their items in place.
     *
     * The **only** observation surface this repository offers. A per-item `observe(itemId)` used to
     * sit beside it, backed by a Room `Flow`; it never acquired a caller, because a screen that has
     * already rendered an item wants the *delta* rather than a second subscription re-reading the
     * table on every write in it (audit 2026-08-08, PERF-28).
     */
    val changes: SharedFlow<UserDataChange>

    /**
     * Marks an item watched or unwatched.
     *
     * Marking watched also clears the resume position and stamps `lastPlayedDate`, matching what
     * the server does for the same action — otherwise the item would come back with a stale
     * progress bar after the next sync.
     */
    suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): AppResult<UserData>

    /** Adds or removes an item from the user's favourites. */
    suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): AppResult<UserData>

    /**
     * Records a resume position, in Jellyfin ticks.
     *
     * Called by the player on every progress tick from M5 onwards; writing it locally as well as
     * reporting it is what makes resume behave identically online and offline.
     */
    suspend fun setPosition(
        itemId: String,
        positionTicks: Long,
    ): AppResult<UserData>
}
