package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import java.util.UUID

/** What a queue update means for this member's player — computed by [decideReconcile]. */
internal sealed interface ReconcileAction {
    /**
     * Nothing to open: either the queue holds nothing playing ([playingEntry] `null`, and the
     * loaded slot is forgotten), or the playing slot is already the open one (every reorder,
     * removal, shuffle and repeat change lands here; the controller still answers the update's
     * ready-owing reasons).
     */
    data class None(
        val playingEntry: SyncPlayQueueEntry?,
    ) : ReconcileAction

    /** No player is attached: ask the app to open one on [entry]. */
    data class RequestLaunch(
        val entry: SyncPlayQueueEntry,
    ) : ReconcileAction

    /** The host already holds this very item: run the handshake around it, reload nothing. */
    data class Adopt(
        val entry: SyncPlayQueueEntry,
        val snapshot: SyncPlayHostSnapshot,
    ) : ReconcileAction

    /** Open [entry] through the host, inside the buffering/ready handshake. */
    data class Load(
        val entry: SyncPlayQueueEntry,
    ) : ReconcileAction
}

/**
 * The pure decision behind `SyncPlayController.reconcile` (audit CPX-15).
 *
 * Four outcomes: nothing to do (the slot is already open — which is every reorder, removal,
 * shuffle and repeat change that leaves the playing item alone), ask the app to open a player
 * (nothing attached), adopt what the host has (this very item is already open, so reloading it
 * would restart playback for nothing), or run the buffering/ready handshake around a load.
 *
 * **The slot, not the item, is the identity**: the same episode queued twice is two slots, and a
 * group jumping from one to the other has to start it again rather than carry on where the first
 * copy had got to. Adoption is therefore only offered before this controller has loaded anything
 * of its own — [loadedPlaylistItemId] is `null` on a fresh join and after a detach, which are
 * exactly the two moments the host might already hold the right item.
 *
 * @param snapshot the host's reading, or `null` when none is attached (the controller only takes
 *   one when the decision can need it: host attached and the playing slot not already open).
 */
@Suppress(
    // A pure decision function: one return per `ReconcileAction`, which is the point of the sealed type.
    "ReturnCount",
)
internal fun decideReconcile(
    queue: SyncPlayGroupQueue,
    loadedPlaylistItemId: UUID?,
    hostAttached: Boolean,
    snapshot: SyncPlayHostSnapshot?,
): ReconcileAction {
    val entry = queue.playingEntry ?: return ReconcileAction.None(playingEntry = null)
    if (entry.playlistItemId == loadedPlaylistItemId) return ReconcileAction.None(entry)
    if (!hostAttached) return ReconcileAction.RequestLaunch(entry)
    if (loadedPlaylistItemId == null && snapshot?.itemId == entry.itemId) {
        return ReconcileAction.Adopt(entry, snapshot)
    }
    return ReconcileAction.Load(entry)
}
