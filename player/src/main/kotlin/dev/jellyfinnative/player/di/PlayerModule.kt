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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
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
     */
    @Provides
    @Singleton
    @DetachedPlayerScope
    fun provideDetachedPlayerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * OkHttp client for media requests only.
     *
     * Separate from anything the SDK uses: media transfers are long-lived, so their timeouts and
     * connection pool have nothing in common with a JSON API call's.
     */
    @Provides
    @Singleton
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
        okHttpClient: OkHttpClient,
    ): DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))
}
