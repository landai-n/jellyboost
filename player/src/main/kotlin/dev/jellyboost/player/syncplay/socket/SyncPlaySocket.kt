package dev.jellyboost.player.syncplay.socket

import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import kotlinx.coroutines.flow.Flow

/**
 * **No `connect()`/`disconnect()`, because the SDK has none.** `DefaultSocketApi` reference-counts
 * subscribers: the socket opens on the first collection of these flows and closes when the last is
 * cancelled. "Connect while in a group" therefore means collecting them for the group's lifetime.
 */
internal interface SyncPlaySocket {
    /** Cold — collecting it opens the socket. */
    val groupUpdates: Flow<SyncPlayGroupEvent>

    /** Cold — collecting it opens the socket. */
    val commands: Flow<SyncPlayCommand>

    /**
     * Hot and independent of the two subscriptions, so it reports `Disconnected` whenever nothing is
     * collecting: only meaningful while [groupUpdates] or [commands] is being collected.
     */
    val connectionState: Flow<SyncPlaySocketState>
}

/** Mirror of the SDK's `SocketApiState`. */
internal sealed interface SyncPlaySocketState {
    /**
     * [error] is `null` for an orderly close (no subscribers left) — the difference between "we hung
     * up" and "the network went away", which decides whether the group is dropped.
     */
    data class Disconnected(
        val error: Throwable?,
    ) : SyncPlaySocketState

    data object Connecting : SyncPlaySocketState

    data object Connected : SyncPlaySocketState
}
