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
fun Flow<ConnectionState>.onlineStateChanges(): Flow<Boolean> =
    map { it.isOnline }
        .distinctUntilChanged()
        .drop(1)
