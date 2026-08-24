package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.network.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * Emits [ConnectionState.isOnline] on **every change of it, in both directions**, and never for the flow's
 * initial value: a screen already loads its data in `init`, so firing on the initial value would make every
 * launch fetch twice — which is why this `drop(1)`s where [onEachOnlineStretch] deliberately does not.
 * `distinctUntilChanged` on *online-ness* keeps a flapping connection from becoming a refetch storm.
 */
fun Flow<ConnectionState>.onlineStateChanges(): Flow<Boolean> = onlineStates().drop(1)

/**
 * Runs [onOnline] **once per stretch of connectivity**, starting with the one the app launches in.
 *
 * The background-worker counterpart to [onlineStateChanges]: a collaborator has done nothing yet, and the
 * state flow replays its current value, so **not** dropping it makes the first collection the app-start check
 * and every later `false → true` the reconnect one — one code path, no separate startup call to keep in sync.
 * [onOffline] is where a caller resets its "already done this stretch" flag.
 *
 * Suspends forever — the underlying flow never completes — so callers launch it on a process-lifetime scope.
 */
suspend fun ConnectionStateProvider.onEachOnlineStretch(
    onOffline: () -> Unit = {},
    onOnline: suspend () -> Unit,
) {
    state.onlineStates().collect { isOnline ->
        if (isOnline) onOnline() else onOffline()
    }
}

/** One emission per change of online-ness, initial value included. */
private fun Flow<ConnectionState>.onlineStates(): Flow<Boolean> =
    map { it.isOnline }
        .distinctUntilChanged()
