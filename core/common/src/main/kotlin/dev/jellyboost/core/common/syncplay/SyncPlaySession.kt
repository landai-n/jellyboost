package dev.jellyboost.core.common.syncplay

import kotlinx.coroutines.flow.StateFlow

/**
 * The seam that lets a `:feature:*` module offer SyncPlay actions without depending on `:player`, where the
 * whole protocol lives. Deliberately tiny: anything the player screen draws belongs to `:player`'s own UI,
 * and putting it here would make every feature module recompile for a protocol change.
 */
interface SyncPlaySession {
    val activeGroup: StateFlow<SyncPlayGroupHandle?>

    /**
     * The group's answer, not this device's: nothing plays here until the server broadcasts the resulting
     * queue. A no-op outside a group.
     *
     * A **list** rather than one id because the queue must be the shape jellyfin-web builds for itself: web
     * expands a single-episode queue locally into the rest of the series and then indexes the server's
     * playlist by that expanded length, so a one-entry queue for an episode makes it read past the end and
     * throw the whole update away. There is deliberately no `startIndex` — the list begins at the item to play.
     */
    suspend fun playForGroup(
        itemIds: List<String>,
        startPositionTicks: Long = 0L,
    )

    /** @param next `true` inserts directly after whatever is playing, `false` appends. */
    suspend fun addToGroupQueue(
        itemId: String,
        next: Boolean,
    )
}

/** [id] is a plain `String`, not a `UUID`, like `JellyfinItem.id`: ids cross this layer as route arguments. */
data class SyncPlayGroupHandle(
    val id: String,
    val name: String,
    /** This user included. */
    val participantCount: Int,
)
