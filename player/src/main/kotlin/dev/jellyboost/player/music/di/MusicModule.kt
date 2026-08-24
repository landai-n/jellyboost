package dev.jellyboost.player.music.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.player.api.AudioStreamUrlFactory
import dev.jellyboost.player.api.SdkAudioStreamUrlFactory
import dev.jellyboost.player.music.ExoMusicPlayerAdapter
import dev.jellyboost.player.music.MusicPlaybackController
import dev.jellyboost.player.music.MusicPlayerPort
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface MusicModule {
    @Binds
    @Singleton
    fun bindMusicPlayerPort(impl: ExoMusicPlayerAdapter): MusicPlayerPort

    @Binds
    @Singleton
    fun bindMusicController(impl: MusicPlaybackController): MusicController

    @Binds
    @Singleton
    fun bindAudioStreamUrlFactory(impl: SdkAudioStreamUrlFactory): AudioStreamUrlFactory
}

@Module
@InstallIn(SingletonComponent::class)
internal object MusicScopeModule {
    // `limitedParallelism(1)` is MusicPlaybackController's only synchronization; do not widen it.
    @Provides
    @Singleton
    @MusicSessionScope
    fun provideMusicSessionScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default.limitedParallelism(1) +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught exception in a music-session coroutine")
                },
        )
}
