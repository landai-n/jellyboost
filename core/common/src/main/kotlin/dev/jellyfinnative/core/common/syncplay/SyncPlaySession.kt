package dev.jellyfinnative.core.common.syncplay

import kotlinx.coroutines.flow.StateFlow

/**
 * What a feature module may know and ask about the SyncPlay group this device is watching with.
 *
 * The whole of SyncPlay lives in `:player` — the protocol, the websocket, the coordinator that
 * drives playback — and `:feature:*` modules must not depend on `:player` (build-logic's feature
 * convention: features depend on `:core:*` and `:data`, never on each other and never on the
 * player). This interface is the seam that keeps that true while still letting a detail page say
 * "watch this together" (docs/notes/syncplay-m11-plan.md, key decision 2).
 *
 * It is deliberately tiny. A feature needs to know **whether** there is a group, so it can offer
 * the actions at all, and it needs the three verbs a browse surface has for a queue. It does not
 * need the queue itself, the participants' readiness, or anything the player screen draws — those
 * belong to `:player`'s own UI, and putting them here would make every feature module recompile for
 * a protocol change.
 *
 * Implemented in `:player` (`ControllerSyncPlaySession`) and bound in `SyncPlayModule`, so the
 * binding is available anywhere in the app graph while the implementation stays where SyncPlay is.
 */
interface SyncPlaySession {
    /**
     * The group this device is in, or `null` when it is not in one.
     *
     * A `StateFlow` rather than a boolean because the two things a feature does with it — offer the
     * group actions, and name the group in their labels — both want the current value, and both
     * have to follow it: a group can be joined or lost from the player screen, from the groups
     * screen, or by the server, while a detail page is open.
     */
    val activeGroup: StateFlow<SyncPlayGroupHandle?>

    /**
     * Replaces the group's queue with [itemIds] and starts it at the first of them.
     *
     * The group's answer, not this device's: nothing plays here until the server broadcasts the
     * resulting queue and the command that goes with it (key decision 11). A no-op outside a group.
     *
     * A **list** rather than one id because the group queue has to be the shape jellyfin-web builds
     * for itself. Web expands a single-episode queue locally into the rest of the series and then
     * indexes the server's playlist by that expanded length, so a one-entry queue for an episode
     * makes it read past the end and throw the whole update away. Callers that mean "just this one"
     * pass a singleton; callers playing an episode pass the run it belongs to.
     *
     * There is deliberately no `startIndex`: the list already begins at the item the group should
     * play, so the playing position is always the first entry.
     *
     * @param startPositionTicks where the group should start — a resume position, or `0`.
     */
    suspend fun playForGroup(
        itemIds: List<String>,
        startPositionTicks: Long = 0L,
    )

    /**
     * Adds [itemId] to the group's queue.
     *
     * @param next `true` to insert it directly after whatever is playing, `false` to append it.
     */
    suspend fun addToGroupQueue(
        itemId: String,
        next: Boolean,
    )
}

/**
 * A group, as much of one as a feature module has any use for.
 *
 * [id] is a plain `String` and not a `UUID` for the same reason `JellyfinItem.id` is: ids cross this
 * layer as route arguments and map keys, and every feature module already speaks that form.
 */
data class SyncPlayGroupHandle(
    val id: String,
    val name: String,
    /** How many people are in the group, this user included. */
    val participantCount: Int,
)
