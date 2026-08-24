package dev.jellyboost.player.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.api.SdkPlayerApi
import dev.jellyboost.player.api.SdkStreamUrlFactory
import dev.jellyboost.player.api.StreamUrlFactory
import dev.jellyboost.player.cast.CastPlaybackCoordinator
import dev.jellyboost.player.cast.CastPlayerHandle
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.cast.CastSessionMonitor
import dev.jellyboost.player.cast.GmsCastSessionMonitor
import dev.jellyboost.player.deviceprofile.MediaCodecProbe
import dev.jellyboost.player.deviceprofile.PlatformMediaCodecProbe
import dev.jellyboost.player.session.ExoPlayerHandle
import dev.jellyboost.player.session.JellyfinAuthInterceptor
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.session.RoutingPlayerHandle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface PlayerBindingsModule {
    @Binds
    @Singleton
    fun bindMediaCodecProbe(impl: PlatformMediaCodecProbe): MediaCodecProbe

    @Binds
    @Singleton
    fun bindPlayerApi(impl: SdkPlayerApi): PlayerApi

    @Binds
    @Singleton
    fun bindStreamUrlFactory(impl: SdkStreamUrlFactory): StreamUrlFactory

    /**
     * `ExoPlayerHandle` stays concretely injectable as well: `PlaybackService` takes it directly, so
     * the local media session and notification disappear while a receiver has the film.
     */
    @Binds
    @Singleton
    fun bindPlayerHandle(impl: RoutingPlayerHandle): PlayerHandle

    @Binds
    @Singleton
    @LocalPlayback
    fun bindLocalPlayerHandle(impl: ExoPlayerHandle): PlayerHandle

    @Binds
    @Singleton
    @CastPlayback
    fun bindCastPlayerHandle(impl: CastPlayerHandle): PlayerHandle

    @Binds
    @Singleton
    fun bindCastSessionMonitor(impl: GmsCastSessionMonitor): CastSessionMonitor

    /**
     * The interface, not the coordinator: `PlayerViewModel` must stay constructible without one
     * (`NoCastPlaybackCoordinator` is its default).
     */
    @Binds
    @Singleton
    fun bindCastPlaybackCoordinator(impl: CastSessionCoordinator): CastPlaybackCoordinator
}

@Module
@InstallIn(SingletonComponent::class)
internal object PlayerProvidersModule {
    /**
     * The scope playback's final report runs on. Never cancelled: it has to outlive the screen that
     * started playback. The [CoroutineExceptionHandler] is required as well as the `SupervisorJob` —
     * a supervisor isolates siblings but an unhandled throw still kills the process.
     */
    @Provides
    @Singleton
    @DetachedPlayerScope
    fun provideDetachedPlayerScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    Timber.e(error, "Uncaught exception in a detached player-scope coroutine")
                },
        )

    /**
     * Media requests only: long-lived transfers share no timeouts or connection pool with a JSON API
     * call. Qualified so no future unqualified `@Inject` picks it up by default.
     */
    @Provides
    @Singleton
    @MediaHttpClient
    fun provideMediaOkHttpClient(authInterceptor: JellyfinAuthInterceptor): OkHttpClient =
        OkHttpClient
            .Builder()
            // Network interceptor, not application: the same-origin check must run per hop, so a
            // redirect off-server is inspected — and refused — too.
            .addNetworkInterceptor(authInterceptor)
            .build()

    /** `DefaultDataSource` wraps the HTTP source so `file://` and `content://` resolve too. */
    @Provides
    @Singleton
    @UnstableApi
    fun provideDataSourceFactory(
        @ApplicationContext context: Context,
        @MediaHttpClient okHttpClient: OkHttpClient,
    ): DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))
}

/** Marks the OkHttp client ExoPlayer transfers media on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class MediaHttpClient

/**
 * The `PlayerHandle` that plays on this device. Qualified rather than concrete so
 * `RoutingPlayerHandle` can be unit tested against fakes, ExoPlayer being unbuildable off a device.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class LocalPlayback

/**
 * The `PlayerHandle` that plays on a Cast receiver. **Always inject as a `Provider`**: constructing
 * it loads the app's first `com.google.android.gms` class.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class CastPlayback
