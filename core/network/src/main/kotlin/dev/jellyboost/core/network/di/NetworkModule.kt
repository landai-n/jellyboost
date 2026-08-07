package dev.jellyboost.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.jellyboost.core.network.ApiClientProvider
import dev.jellyboost.core.network.JellyfinApiFacade
import dev.jellyboost.core.network.SdkJellyfinApiFacade
import dev.jellyboost.core.network.SignOutHook
import dev.jellyboost.core.network.connectivity.AndroidConnectivityMonitor
import dev.jellyboost.core.network.connectivity.ConnectivityMonitor
import dev.jellyboost.core.network.connectivity.SdkServerProbeApi
import dev.jellyboost.core.network.connectivity.ServerProbeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Singleton

/**
 * Hilt bindings for `:core:network`.
 *
 * `ApiClientProvider`, `ServerDiscoveryRepository`, `AuthRepository`, `SessionRepository` and
 * `SessionStateHolder` are all constructor-injectable and need no entry here.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    /** Binds the SDK-backed implementation of the API seam the repositories consume. */
    @Binds
    @Singleton
    fun bindJellyfinApiFacade(impl: SdkJellyfinApiFacade): JellyfinApiFacade

    /** Binds the connectivity monitor to its `ConnectivityManager` implementation (M6). */
    @Binds
    @Singleton
    fun bindConnectivityMonitor(impl: AndroidConnectivityMonitor): ConnectivityMonitor

    /** Binds the reachability probe's single SDK call (M6). */
    @Binds
    @Singleton
    fun bindServerProbeApi(impl: SdkServerProbeApi): ServerProbeApi

    /**
     * Declares the [SignOutHook] set so `SessionRepository` injects an empty set when no other
     * module contributes one — the contributors live in feature/player modules this one cannot see.
     */
    @Multibinds
    fun signOutHooks(): Set<SignOutHook>
}

/** Provides the dispatchers and scopes `:core:network` runs its background work on. */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkDispatchersModule {
    /** The dispatcher every network/discovery call hops onto. */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** The dispatcher CPU-bound projection work hops onto, so that it never runs on Main. */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * The main thread, for the Media3 components that assert they are driven from one thread with a
     * `Looper`. See [MainDispatcher] — it is a seam for tests as much as a binding.
     */
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * The process-lifetime scope for work that outlives any screen: the connectivity monitor, the
     * reachability probe loop, and the fire-and-forget browse-cache write-through.
     *
     * A [SupervisorJob] so one failing child cannot take the app's connectivity monitoring down
     * with it. Never cancelled — it dies with the process.
     *
     * The [CoroutineExceptionHandler] is the other half of that promise. `SupervisorJob` stops a
     * failing child from cancelling its siblings, but it does not *handle* the exception: without a
     * handler an uncaught throw from any child reaches the thread's default handler and takes the
     * process down. Every current child is defensive, but nothing keeps the next one so, and the
     * most exposed of them is the reachability probe loop — the app's only offline detector.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                dispatcher +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught exception in an application-scope coroutine")
                },
        )
}

/**
 * Exposes the SDK [ApiClient] to the rest of the Hilt graph (`:feature:*` ViewModels reach it
 * transitively through `:data`).
 *
 * There is exactly one [ApiClient] instance for the whole app, owned by [ApiClientProvider] and
 * re-pointed in place via `ApiClient.update` as the user signs in, signs out or switches servers.
 * This provider hands out that same mutable instance rather than constructing one of its own.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ApiClientModule {
    /** The single, mutable SDK client owned by [ApiClientProvider]. */
    @Provides
    @Singleton
    fun provideApiClient(provider: ApiClientProvider): ApiClient = provider.apiClient
}
