package dev.jellyfinnative.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.core.network.JellyfinApiFacade
import dev.jellyfinnative.core.network.SdkJellyfinApiFacade
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
