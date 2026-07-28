package dev.jellyfinnative.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.OnlineJellyfinRepository
import dev.jellyfinnative.data.mapper.ImageUrlFactory
import dev.jellyfinnative.data.mapper.SdkImageUrlFactory
import javax.inject.Singleton

/**
 * Hilt bindings for `:data`.
 *
 * [JellyfinRepository] is bound to the online implementation for now. In M6 this binding moves to
 * `DelegatingJellyfinRepository`, which picks between the online and offline implementations per
 * call based on `ConnectionState` (docs/PLAN.md, "Data layer").
 *
 * The `org.jellyfin.sdk.api.client.ApiClient` these implementations depend on is provided by
 * `:core:network` (the session layer owns its lifecycle and access token).
 */
@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    /** Binds the media-browsing repository. */
    @Binds
    @Singleton
    fun bindJellyfinRepository(impl: OnlineJellyfinRepository): JellyfinRepository

    /** Binds image URL construction to the SDK's `imageApi` URL builders. */
    @Binds
    @Singleton
    fun bindImageUrlFactory(impl: SdkImageUrlFactory): ImageUrlFactory
}
