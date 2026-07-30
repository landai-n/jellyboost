package dev.jellyfinnative.player.syncplay.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.player.syncplay.api.SdkSyncPlayApi
import dev.jellyfinnative.player.syncplay.api.SyncPlayApi
import dev.jellyfinnative.player.syncplay.socket.SdkSyncPlaySocket
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocket
import javax.inject.Singleton

/**
 * Wires the SyncPlay SDK facades to their interfaces, modelled on `PlayerBindingsModule`.
 *
 * Both are `@Singleton`: the REST facade holds nothing but the client, and the socket facade must
 * be shared so the SDK's subscriber reference counting sees one consumer rather than one per
 * injection point.
 *
 * `SyncPlayTimeSync` is constructor-injectable and `@Singleton`-annotated, so it needs no binding
 * here. The controller's supervisor scope arrives with the controller itself (M11 Phase 2).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface SyncPlayModule {
    @Binds
    @Singleton
    fun bindSyncPlayApi(impl: SdkSyncPlayApi): SyncPlayApi

    @Binds
    @Singleton
    fun bindSyncPlaySocket(impl: SdkSyncPlaySocket): SyncPlaySocket
}
