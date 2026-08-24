package dev.jellyboost.player.syncplay.di

import javax.inject.Qualifier

/**
 * Process-lifetime: group membership outlives the player screen, so the websocket collection, the
 * ping loop and scheduled commands must never hang off `viewModelScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SyncPlayScope
