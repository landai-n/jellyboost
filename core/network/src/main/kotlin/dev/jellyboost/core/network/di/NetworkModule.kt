package dev.jellyboost.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.DefaultDispatcher
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.di.MainDispatcher
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

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {
    @Binds
    @Singleton
    fun bindJellyfinApiFacade(impl: SdkJellyfinApiFacade): JellyfinApiFacade

    @Binds
    @Singleton
    fun bindConnectivityMonitor(impl: AndroidConnectivityMonitor): ConnectivityMonitor

    @Binds
    @Singleton
    fun bindServerProbeApi(impl: SdkServerProbeApi): ServerProbeApi

    /** Declared so `SessionRepository` injects an empty set: the contributors live in modules this one cannot see. */
    @Multibinds
    fun signOutHooks(): Set<SignOutHook>
}

/**
 * The qualifiers live in `:core:common` — pure `javax.inject` annotations every module can name — while the
 * bindings stay here, because a `@Provides` needs Hilt and `:core:common` has no Dagger processor on it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkDispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** Media3 asserts it is driven from one thread with a `Looper`; [MainDispatcher] is also a test seam. */
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * Process-lifetime scope, never cancelled. [SupervisorJob] keeps one failing child from taking the app's
     * connectivity monitoring down, but it does not *handle* the exception: without the
     * [CoroutineExceptionHandler] an uncaught throw from any child reaches the thread's default handler and
     * takes the process down.
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
 * There is exactly one [ApiClient] instance for the whole app, owned by [ApiClientProvider] and re-pointed in
 * place as the user signs in, signs out or switches servers. This hands out that same mutable instance.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ApiClientModule {
    @Provides
    @Singleton
    fun provideApiClient(provider: ApiClientProvider): ApiClient = provider.apiClient
}
