package dev.jellyboost.player.syncplay.socket

import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import kotlinx.coroutines.flow.Flow

/**
 * The two SyncPlay websocket streams, behind one seam.
 *
 * **There is no `connect()`/`disconnect()` here because the SDK has none.** `SocketApi` (verified
 * against jellyfin-sdk 1.8.12) exposes only `state`, `subscribeAll()` and `subscribe(KClass)`, and
 * `DefaultSocketApi` reference-counts its subscribers: the socket opens when the first of these
 * flows is collected and closes when the last collection is cancelled, with reconnect and
 * keep-alive handled inside the SDK. So "connect while in a group" (docs/PLAN.md M11, key decision
 * 3) is implemented by *collecting* these flows for exactly as long as the group lasts.
 */
interface SyncPlaySocket {
    /** Group lifecycle, state, queue and membership updates. Cold — collecting it opens the socket. */
    val groupUpdates: Flow<SyncPlayGroupEvent>

    /** Transport commands (unpause/pause/seek/stop). Cold — collecting it opens the socket. */
    val commands: Flow<SyncPlayCommand>

    /**
     * The socket's connection state.
     *
     * Hot and independent of the two subscriptions: it reports `Disconnected` when nothing is
     * collecting. Only meaningful while [groupUpdates] or [commands] is being collected, which is
     * exactly when the controller cares (confirmed connection loss mid-group → leave the group).
     */
    val connectionState: Flow<SyncPlaySocketState>
}

/** Mirror of the SDK's `SocketApiState`. */
sealed interface SyncPlaySocketState {
    /**
     * No subscribers, or the connection dropped and the SDK has not re-established it yet.
     *
     * [error] is what tore the socket down, when the SDK knows — `null` for an orderly close (no
     * subscribers left). It is the difference between "we hung up" and "the network went away",
     * which is exactly what decides whether a group is dropped.
     */
    data class Disconnected(
        val error: Throwable?,
    ) : SyncPlaySocketState

    data object Connecting : SyncPlaySocketState

    data object Connected : SyncPlaySocketState
}
