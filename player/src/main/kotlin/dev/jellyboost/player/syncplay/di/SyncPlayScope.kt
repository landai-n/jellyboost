package dev.jellyboost.player.syncplay.di

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] SyncPlay coordination runs in.
 *
 * Group membership deliberately outlives the player screen (docs/notes/syncplay-m11-plan.md, key
 * decision 5): the websocket collection, the ping loop and a scheduled command all have to keep
 * running while the app is backgrounded and while no `PlayerViewModel` exists at all. So none of it
 * can hang off `viewModelScope` — it hangs off this, modelled on
 * `dev.jellyboost.player.di.DetachedPlayerScope`.
 *
 * Injected rather than created ad hoc so tests can substitute a `TestScope` and drive the join
 * handshake, the ping cadence and the scheduled commands on virtual time.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SyncPlayScope
