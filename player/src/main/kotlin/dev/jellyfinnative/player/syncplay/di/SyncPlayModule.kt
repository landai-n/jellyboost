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
import dev.jellyfinnative.player.syncplay.socket.OkHttpSyncPlaySocket
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import timber.log.Timber
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Wires the SyncPlay SDK facades to their interfaces, modelled on `PlayerBindingsModule`.
 *
 * Both are `@Singleton`: the REST facade holds nothing but the client, and the socket facade must
 * be shared so its connection reference counting sees one consumer rather than one per injection
 * point.
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

    /**
     * **Not** `SdkSyncPlaySocket` (DECISIONS.md 2026-07-31): the SDK's socket routes received
     * messages through a conflated `StateFlow` and loses the first of any back-to-back pair —
     * which is precisely how the server sends every SyncPlay transport action. The SDK-backed
     * implementation is kept in the tree as the reference for that defect.
     */
    @Binds
    @Singleton
    fun bindSyncPlaySocket(impl: OkHttpSyncPlaySocket): SyncPlaySocket

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

    /**
     * The OkHttp client `OkHttpSyncPlaySocket` opens its websocket with.
     *
     * Its own rather than `@MediaHttpClient`'s: that one exists for long-lived media transfers and
     * carries `JellyfinAuthInterceptor`, which would rewrite the `Authorization` header the socket
     * builds for itself. Defaults are left alone deliberately — OkHttp does not apply a read
     * timeout to a websocket's reader, so an idle group is not a dropped connection, and the
     * server-driven `ForceKeepAlive` cadence is what keeps the session alive.
     *
     * Exposed as [WebSocket.Factory] rather than as the client, so the socket can be tested
     * against a fake without an HTTP stack.
     */
    @Provides
    @Singleton
    @SyncPlaySocketClient
    fun provideSyncPlaySocketFactory(): WebSocket.Factory = OkHttpClient.Builder().build()
}

/** Marks the OkHttp websocket factory SyncPlay's own socket connects with. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SyncPlaySocketClient
