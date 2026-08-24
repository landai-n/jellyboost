package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import java.util.UUID

internal sealed interface ReconcileAction {
    /** Nothing to open; the controller still has to answer the update's ready-owing reasons. */
    data class None(
        val playingEntry: SyncPlayQueueEntry?,
    ) : ReconcileAction

    data class RequestLaunch(
        val entry: SyncPlayQueueEntry,
    ) : ReconcileAction

    /** Run the handshake around what the host already holds; reloading would restart playback. */
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
 * **The slot, not the item, is the identity**: the same episode queued twice is two slots, and a group
 * jumping between them must restart it rather than carry on. Adoption is therefore offered only while
 * [loadedPlaylistItemId] is `null` — a fresh join or a detach.
 *
 * @param snapshot `null` when no host is attached.
 */
@Suppress(
    // One return per `ReconcileAction`, which is the point of the sealed type.
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
