package dev.jellyboost.data.downloads.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.SdkDownloadApi
import dev.jellyboost.data.downloads.engine.AndroidMeteredConnection
import dev.jellyboost.data.downloads.engine.AudioSidecarExtractor
import dev.jellyboost.data.downloads.engine.DownloadHttpClient
import dev.jellyboost.data.downloads.engine.MeteredConnection
import dev.jellyboost.data.downloads.engine.TransformerAudioSidecarExtractor
import dev.jellyboost.data.downloads.impl.DownloadRepositoryImpl
import dev.jellyboost.data.downloads.plan.DownloadUrlFactory
import dev.jellyboost.data.downloads.plan.SdkDownloadUrlFactory
import dev.jellyboost.data.downloads.storage.AndroidStorageVolumeProvider
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.FileDownloadStorage
import dev.jellyboost.data.downloads.storage.StorageVolumeProvider
import dev.jellyboost.data.downloads.work.DownloadScheduler
import dev.jellyboost.data.downloads.work.WorkManagerDownloadScheduler
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadsModule {
    @Binds
    @Singleton
    fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    fun bindDownloadApi(impl: SdkDownloadApi): DownloadApi

    @Binds
    @Singleton
    fun bindDownloadUrlFactory(impl: SdkDownloadUrlFactory): DownloadUrlFactory

    @Binds
    @Singleton
    fun bindDownloadStorage(impl: FileDownloadStorage): DownloadStorage

    @Binds
    @Singleton
    fun bindStorageVolumeProvider(impl: AndroidStorageVolumeProvider): StorageVolumeProvider

    @Binds
    @Singleton
    fun bindMeteredConnection(impl: AndroidMeteredConnection): MeteredConnection

    /** An interface so the queue's tests can run the whole AUDIO path without a `Looper`, muxer or device. */
    @Binds
    @Singleton
    fun bindAudioSidecarExtractor(impl: TransformerAudioSidecarExtractor): AudioSidecarExtractor

    @Binds
    @Singleton
    fun bindDownloadScheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler
}

@Module
@InstallIn(SingletonComponent::class)
internal object DownloadHttpModule {
    /**
     * A client of its own, not the SDK's. Two settings make it a *download* client: a generous
     * **between-bytes** read timeout, because a healthy multi-gigabyte transfer legitimately holds one
     * response open for an hour (OkHttp's `readTimeout` bounds the silence between two reads, never
     * the call's total duration); and redirects followed, because `/Items/{id}/Download` can redirect
     * to a storage backend.
     *
     * An unbounded read timeout of `0` would let a half-open TCP connection park the copy loop in
     * `input.read()` forever, holding the process-wide drain lease with a foreground notification at
     * N % until the process died. Two minutes of *total silence* from a server that is supposed to be
     * streaming is a dead connection, not a slow one.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * `FileDownloader` depends on the narrower [Call.Factory] rather than on `OkHttpClient`, which is
     * what lets its unit tests hand it a canned response with no server involved. Qualified because an
     * unqualified `Call.Factory` binding would be the *only* one in the graph, so a future unqualified
     * `@Inject` would silently receive this download client instead of failing to compile.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadCallFactory(
        @DownloadHttpClient client: OkHttpClient,
    ): Call.Factory = client

    private const val CONNECT_TIMEOUT_SECONDS = 30L

    /**
     * The longest silence between two bytes a live transfer is allowed. Internal so the test can pin
     * that the built client actually carries it — an unbounded read is what wedges the queue.
     */
    internal const val READ_TIMEOUT_SECONDS = 120L

    /** Downloads send no request bodies; this only bounds a pathological handshake. */
    internal const val WRITE_TIMEOUT_SECONDS = 30L
}
