package dev.jellyfinnative.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.core.network.ApiClientProvider
import dev.jellyfinnative.core.network.JellyfinApiFacade
import dev.jellyfinnative.core.network.SdkJellyfinApiFacade
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jellyfin.sdk.api.client.ApiClient
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
}

/** Provides the dispatchers `:core:network` runs its blocking work on. */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkDispatchersModule {
    /** The dispatcher every network/discovery call hops onto. */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
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
