package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.network.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * Emits the new [ConnectionState.isOnline] on **every change of it, in both directions**, and never
 * for the flow's initial value.
 *
 * This is the "what you are showing may no longer be what the app can serve" signal. It fires both
 * ways on purpose: the server coming back makes an offline screen stale, and the user pinning
 * offline mode — or walking out of Wi-Fi range — makes an online one stale in exactly the same way,
 * only worse, because those rows link to media the app can no longer play.
 *
 * A screen already loads its data once, in its `init`, so a signal that also fired on the initial
 * value would make every ordinary launch fetch everything twice — which is why this deliberately
 * `drop(1)`s where `UserDataSyncTrigger` deliberately does not (see DECISIONS.md, 2026-07-29).
 *
 * `distinctUntilChanged` on *online-ness* is what keeps a flapping connection from becoming a
 * refetch storm: swapping between two offline reasons is not a change, and neither is a repeated
 * `ONLINE`.
 */
fun Flow<ConnectionState>.onlineStateChanges(): Flow<Boolean> = onlineStates().drop(1)

/**
 * Runs [onOnline] **once per stretch of connectivity**, starting with the one the app launches in,
 * and [onOffline] each time the connection goes away.
 *
 * The background-worker counterpart to [onlineStateChanges], and the difference between them is the
 * whole of the design: a *screen* has already loaded its data in `init`, so a signal that also fired
 * on the flow's current value would make every launch fetch twice — hence the `drop(1)` there. A
 * *background collaborator* is in the opposite position. It has done nothing yet, and the two
 * moments it must act on are "the app started, possibly with work left over from last time" and
 * "the connection came back". The state flow replays its current value, so **not** dropping it
 * makes the first collection the app-start check and every later `false → true` the reconnect one:
 * one code path, both cases, no separate startup call to keep in sync (DECISIONS.md, 2026-07-29).
 *
 * `distinctUntilChanged` on online-ness is what keeps a flapping probe from becoming a burst of
 * passes; [onOffline] is where a caller resets whatever "already done this stretch" flag it keeps,
 * which is what makes the *next* `true` meaningful rather than redundant.
 *
 * Suspends forever — the underlying flow never completes — so callers launch it on a scope that
 * lives as long as the process.
 *
 * @param onOffline run on every loss of connectivity. Defaults to doing nothing, for the caller
 *   whose work is naturally once-per-edge rather than once-per-stretch.
 * @param onOnline run at app start (if online) and on every return to the network.
 */
suspend fun ConnectionStateProvider.onEachOnlineStretch(
    onOffline: () -> Unit = {},
    onOnline: suspend () -> Unit,
) {
    state.onlineStates().collect { isOnline ->
        if (isOnline) onOnline() else onOffline()
    }
}

/** Online-ness, one emission per change of it, initial value included. */
private fun Flow<ConnectionState>.onlineStates(): Flow<Boolean> =
    map { it.isOnline }
        .distinctUntilChanged()
