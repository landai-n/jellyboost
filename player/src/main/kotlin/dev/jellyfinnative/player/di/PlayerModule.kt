package dev.jellyfinnative.player.di

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
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.api.SdkPlayerApi
import dev.jellyfinnative.player.api.SdkStreamUrlFactory
import dev.jellyfinnative.player.api.StreamUrlFactory
import dev.jellyfinnative.player.deviceprofile.MediaCodecProbe
import dev.jellyfinnative.player.deviceprofile.PlatformMediaCodecProbe
import dev.jellyfinnative.player.session.ExoPlayerHandle
import dev.jellyfinnative.player.session.JellyfinAuthInterceptor
import dev.jellyfinnative.player.session.PlayerHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Qualifier
import javax.inject.Singleton

/** Wires the `:player` implementations to the interfaces the rest of the module depends on. */
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

    @Binds
    @Singleton
    fun bindPlayerHandle(impl: ExoPlayerHandle): PlayerHandle
}

/** Objects the player needs that are not constructor-injectable. */
@Module
@InstallIn(SingletonComponent::class)
internal object PlayerProvidersModule {
    /**
     * The scope playback's final report runs on.
     *
     * `SupervisorJob` so one failed report cannot cancel the next session's, and it is never
     * cancelled — the whole point is that it outlives whatever screen started the playback.
     *
     * The [CoroutineExceptionHandler] catches what the supervisor does not: a supervisor isolates
     * siblings from a failure, but an *unhandled* one still reaches the default handler and kills
     * the process. A stop-report that throws must cost the report, not the app.
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
     * The thread every `PlayerHandle` transport call has to be made on.
     *
     * See [MainDispatcher]: SyncPlay drives the shared player from a background scope, and Media3
     * will throw if a seek or a pause arrives on any thread but the one the player was built on.
     */
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * OkHttp client for media requests only.
     *
     * Separate from anything the SDK uses: media transfers are long-lived, so their timeouts and
     * connection pool have nothing in common with a JSON API call's.
     *
     * Qualified (audit ARCH-06): `:data:downloads` already qualifies its own no-timeout download
     * client, and leaving this one the graph's only unqualified `OkHttpClient` would make it the
     * silent default for any future unqualified `@Inject` — explicit is safer than "whichever
     * client happened to stay nameless".
     */
    @Provides
    @Singleton
    @MediaHttpClient
    fun provideMediaOkHttpClient(authInterceptor: JellyfinAuthInterceptor): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(authInterceptor)
            .build()

    /**
     * Where ExoPlayer gets its bytes.
     *
     * `DefaultDataSource` wraps the HTTP source so `file://` and `content://` URIs resolve too —
     * that is what M8's downloaded-file playback will need, with no change here.
     */
    @Provides
    @Singleton
    @UnstableApi
    fun provideDataSourceFactory(
        @ApplicationContext context: Context,
        @MediaHttpClient okHttpClient: OkHttpClient,
    ): DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))
}

/** Marks the OkHttp client ExoPlayer transfers media on (audit ARCH-06). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaHttpClient
