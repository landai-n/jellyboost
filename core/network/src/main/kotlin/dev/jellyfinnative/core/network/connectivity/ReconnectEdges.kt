package dev.jellyfinnative.core.network.connectivity

import dev.jellyfinnative.core.network.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Emits once for every **`false → true` transition** of [ConnectionState.isOnline], and never for
 * the flow's initial value.
 *
 * This is the "the server is reachable *again*" signal, not the "we are online" one. A screen
 * already loads its data once, in its `init`, so a signal that also fired on the initial value
 * would make every ordinary launch fetch everything twice — which is why this deliberately
 * `drop(1)`s where `UserDataSyncTrigger` deliberately does not (see DECISIONS.md, 2026-07-29).
 *
 * `distinctUntilChanged` on *online-ness* is what keeps a flapping connection from becoming a
 * refetch storm: swapping between two offline reasons is not an edge, and neither is a repeated
 * `ONLINE`.
 */
fun Flow<ConnectionState>.reconnectEdges(): Flow<Unit> =
    map { it.isOnline }
        .distinctUntilChanged()
        .drop(1)
        .filter { isOnline -> isOnline }
        .map { }
