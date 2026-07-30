package dev.jellyfinnative.player.syncplay.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.core.common.syncplay.SyncPlaySession
import dev.jellyfinnative.player.syncplay.ControllerSyncPlaySession
import dev.jellyfinnative.player.syncplay.api.SdkSyncPlayApi
import dev.jellyfinnative.player.syncplay.api.SyncPlayApi
import dev.jellyfinnative.player.syncplay.socket.SdkSyncPlaySocket
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Singleton

/**
 * Wires the SyncPlay SDK facades to their interfaces, modelled on `PlayerBindingsModule`.
 *
 * Both are `@Singleton`: the REST facade holds nothing but the client, and the socket facade must
 * be shared so the SDK's subscriber reference counting sees one consumer rather than one per
 * injection point.
 *
 * `SyncPlayTimeSync`, the scheduler, the drift monitor, the pinger, the status holder and the
 * controller are all constructor-injectable and `@Singleton`-annotated, so they need no binding
 * here — only the scope they run in does.
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

    /**
     * The cross-feature contract (M11 Phase 4, key decision 2).
     *
     * Bound here rather than in `:app` because this is where the implementation is: Hilt aggregates
     * modules across the whole app graph, so `:feature:detail` — which cannot see `:player` at all —
     * gets the binding simply by being in the same application component.
     */
    @Binds
    @Singleton
    fun bindSyncPlaySession(impl: ControllerSyncPlaySession): SyncPlaySession
}

/** The scope SyncPlay coordination runs in. */
@Module
@InstallIn(SingletonComponent::class)
internal object SyncPlayScopeModule {
    /**
     * The process-lifetime scope the controller, the ping loop and every scheduled command run in.
     *
     * Modelled on `PlayerProvidersModule.provideDetachedPlayerScope`, and for the same reason: this
     * work has to survive the player screen. It is never cancelled — leaving a group cancels the
     * controller's own per-session child job instead, so the singleton scope stays usable for the
     * next group.
     *
     * `SupervisorJob` so a failed ping cannot take the websocket collection down with it, and a
     * [CoroutineExceptionHandler] because a supervisor isolates siblings from a failure without
     * *handling* it — an unhandled throw would still reach the default handler and kill the process.
     */
    @Provides
    @Singleton
    @SyncPlayScope
    fun provideSyncPlayScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught exception in a SyncPlay-scope coroutine")
                },
        )
}
