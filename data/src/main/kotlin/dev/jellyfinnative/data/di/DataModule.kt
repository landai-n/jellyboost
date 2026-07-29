package dev.jellyfinnative.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.data.DelegatingJellyfinRepository
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.mapper.ArtworkRequestWidths
import dev.jellyfinnative.data.mapper.ImageUrlFactory
import dev.jellyfinnative.data.mapper.SdkImageUrlFactory
import javax.inject.Singleton

/**
 * Hilt bindings for `:data`.
 *
 * [JellyfinRepository] is bound to `DelegatingJellyfinRepository`, which picks between the online
 * (SDK) and offline (Room) implementations per call based on `ConnectionState` (docs/PLAN.md,
 * "Data layer"). Both implementations are constructor-injectable and are reached only through it —
 * nothing outside `:data` should inject either one directly.
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
    fun bindJellyfinRepository(impl: DelegatingJellyfinRepository): JellyfinRepository

    /** Binds image URL construction to the SDK's `imageApi` URL builders. */
    @Binds
    @Singleton
    fun bindImageUrlFactory(impl: SdkImageUrlFactory): ImageUrlFactory

    companion object {
        /**
         * Resolves the artwork request widths once, from the display the app is actually running
         * on, so the server sends thumbnails at the size they are drawn at.
         */
        @Provides
        @Singleton
        fun provideArtworkRequestWidths(
            @ApplicationContext context: Context,
        ): ArtworkRequestWidths = ArtworkRequestWidths.forDensity(context.resources.displayMetrics.density)
    }
}
