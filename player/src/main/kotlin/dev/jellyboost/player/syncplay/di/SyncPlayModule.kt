package dev.jellyboost.player.syncplay.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.player.syncplay.ControllerSyncPlaySession
import dev.jellyboost.player.syncplay.api.SdkSyncPlayApi
import dev.jellyboost.player.syncplay.api.SyncPlayApi
import dev.jellyboost.player.syncplay.socket.OkHttpSyncPlaySocket
import dev.jellyboost.player.syncplay.socket.SyncPlaySocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import timber.log.Timber
import java.util.concurrent.TimeUnit
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
     * **Single-threaded, and that is the controller's synchronization** (audit SP-07/SP-01/SP-08):
     * `SyncPlayController` and `SyncPlayCommandScheduler` keep a session's bookkeeping in plain
     * `var`s and mutate it from half a dozen concurrent collectors, which on the default pool is a
     * data race on every field. `limitedParallelism(1)` serialises every coroutine on this scope —
     * one at a time, with a happens-before edge between them — so those fields need no locks as
     * long as *everything* that touches them runs here. The controller's main-thread entry points
     * (`attachHost`, `detachHost`, `onHostBuffering`, `leaveGroup`, `onAppForegrounded`) hop onto
     * this scope for exactly that reason; only `PlayerHandle` calls leave it, via the main
     * dispatcher.
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
                Dispatchers.Default.limitedParallelism(1) +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught exception in a SyncPlay-scope coroutine")
                },
        )

    /**
     * The OkHttp client `OkHttpSyncPlaySocket` opens its websocket with.
     *
     * Its own rather than `@MediaHttpClient`'s: that one exists for long-lived media transfers and
     * carries `JellyfinAuthInterceptor`, which would rewrite the `Authorization` header the socket
     * builds for itself. No read timeout is set deliberately — OkHttp does not apply one to a
     * websocket's reader, so an idle group is not a dropped connection, and the server-driven
     * `ForceKeepAlive` cadence is what keeps the *session* alive.
     *
     * The [OkHttpClient.pingIntervalMillis] is what keeps the *connection* honest (audit SP-16):
     * the app-level keep-alive is one-directional and `send` on a half-open TCP connection succeeds
     * into the send buffer for minutes, so without RFC 6455 pings a NAT timeout or a Wi-Fi↔cellular
     * handover leaves `connectionState` reading `Connected` for ever. A missed pong fails the
     * socket, which is what lets `OkHttpSyncPlaySocket`'s reconnect loop and the controller's
     * `markTrouble` do their jobs.
     *
     * Exposed as [WebSocket.Factory] rather than as the client, so the socket can be tested
     * against a fake without an HTTP stack.
     */
    @Provides
    @Singleton
    @SyncPlaySocketClient
    fun provideSyncPlaySocketFactory(): WebSocket.Factory =
        OkHttpClient
            .Builder()
            .pingInterval(SOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

    /** RFC 6455 ping cadence — frequent enough to beat NAT idle timeouts, cheap enough to ignore. */
    private const val SOCKET_PING_INTERVAL_SECONDS = 30L
}

/** Marks the OkHttp websocket factory SyncPlay's own socket connects with. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SyncPlaySocketClient
