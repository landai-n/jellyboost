package dev.jellyboost.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.data.DelegatingJellyfinRepository
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.mapper.ArtworkRequestWidths
import dev.jellyboost.data.mapper.ImageUrlFactory
import dev.jellyboost.data.mapper.SdkImageUrlFactory
import dev.jellyboost.data.music.MusicApi
import dev.jellyboost.data.music.SdkMusicApi
import javax.inject.Singleton

/**
 * [JellyfinRepository] must be reached only through `DelegatingJellyfinRepository` — nothing outside
 * `:data` may inject the online or offline implementation directly.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    @Singleton
    fun bindJellyfinRepository(impl: DelegatingJellyfinRepository): JellyfinRepository

    @Binds
    @Singleton
    fun bindImageUrlFactory(impl: SdkImageUrlFactory): ImageUrlFactory

    @Binds
    @Singleton
    fun bindMusicApi(impl: SdkMusicApi): MusicApi

    companion object {
        /** Resolved once, from the display the app actually runs on. */
        @Provides
        @Singleton
        fun provideArtworkRequestWidths(
            @ApplicationContext context: Context,
        ): ArtworkRequestWidths = ArtworkRequestWidths.forDensity(context.resources.displayMetrics.density)
    }
}
