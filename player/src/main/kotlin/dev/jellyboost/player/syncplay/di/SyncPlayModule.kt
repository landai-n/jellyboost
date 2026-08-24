package dev.jellyboost.player.syncplay.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import dev.jellyboost.core.network.SignOutHook
import dev.jellyboost.player.syncplay.ControllerSyncPlaySession
import dev.jellyboost.player.syncplay.SyncPlaySignOutHook
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
 * The socket facade must stay `@Singleton`: its connection reference counting has to see one
 * consumer, not one per injection point.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface SyncPlayModule {
    @Binds
    @Singleton
    fun bindSyncPlayApi(impl: SdkSyncPlayApi): SyncPlayApi

    /**
     * **Not** `SdkSyncPlaySocket`: the SDK's socket conflates received messages through a
     * `StateFlow` and loses the first of any back-to-back pair — how the server sends every
     * SyncPlay transport action.
     */
    @Binds
    @Singleton
    fun bindSyncPlaySocket(impl: OkHttpSyncPlaySocket): SyncPlaySocket

    /**
     * `:feature:detail` cannot see `:player`, but Hilt aggregates modules across the app graph, so
     * the binding reaches it from here.
     */
    @Binds
    @Singleton
    fun bindSyncPlaySession(impl: ControllerSyncPlaySession): SyncPlaySession

    /** A pre-revocation hook: the group leave must be sent while the access token still works. */
    @Binds
    @IntoSet
    fun bindSyncPlaySignOutHook(impl: SyncPlaySignOutHook): SignOutHook
}

@Module
@InstallIn(SingletonComponent::class)
internal object SyncPlayScopeModule {
    /**
     * Process-lifetime, never cancelled: leaving a group cancels the controller's own per-session
     * child job instead, so the scope stays usable for the next group.
     *
     * **`limitedParallelism(1)` is the controller's synchronization.** `SyncPlayController` and
     * `SyncPlayCommandScheduler` keep session bookkeeping in plain `var`s mutated from many
     * collectors; those fields need no locks only as long as *everything* touching them runs here.
     *
     * The [CoroutineExceptionHandler] is required as well as the `SupervisorJob`: a supervisor
     * isolates siblings from a failure without handling it, and an unhandled throw kills the process.
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
     * Its own client, not `@MediaHttpClient`'s: that one carries `JellyfinAuthInterceptor`, which
     * would rewrite the `Authorization` header the socket builds for itself.
     *
     * The RFC 6455 ping interval is what keeps the connection honest — `send` on a half-open TCP
     * connection succeeds into the send buffer for minutes, so without pings a NAT timeout or a
     * Wi-Fi↔cellular handover leaves `connectionState` reading `Connected` for ever.
     *
     * Exposed as [WebSocket.Factory] so the socket can be tested against a fake with no HTTP stack.
     */
    @Provides
    @Singleton
    @SyncPlaySocketClient
    fun provideSyncPlaySocketFactory(): WebSocket.Factory =
        OkHttpClient
            .Builder()
            .pingInterval(SOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

    /** Frequent enough to beat NAT idle timeouts, cheap enough to ignore. */
    private const val SOCKET_PING_INTERVAL_SECONDS = 30L
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SyncPlaySocketClient
