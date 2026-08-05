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

/**
 * Wires the music queue's implementations to the seams above them, modelled on `SyncPlayModule`.
 *
 * [MusicController] is the one binding that leaves `:player`: it is declared in `:core:common`, so
 * binding it here — where the implementation is — makes it available to `:app`'s mini-player and
 * to `:feature:music` without either module ever depending on the player (key decision 2).
 */
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

/** The scope the music queue is orchestrated on; see [MusicSessionScope]. */
@Module
@InstallIn(SingletonComponent::class)
internal object MusicScopeModule {
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
