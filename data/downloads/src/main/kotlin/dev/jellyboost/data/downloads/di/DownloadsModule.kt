package dev.jellyboost.data.downloads.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.SdkDownloadApi
import dev.jellyboost.data.downloads.engine.AudioSidecarExtractor
import dev.jellyboost.data.downloads.engine.DownloadHttpClient
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

/** Hilt bindings for `:data:downloads`. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadsModule {
    /** The one type `:feature:*` modules inject. */
    @Binds
    @Singleton
    fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    /** Binds the full-fields item re-fetch behind an enqueue. */
    @Binds
    @Singleton
    fun bindDownloadApi(impl: SdkDownloadApi): DownloadApi

    /** Binds URL construction to the SDK's builders. */
    @Binds
    @Singleton
    fun bindDownloadUrlFactory(impl: SdkDownloadUrlFactory): DownloadUrlFactory

    /** Binds storage to the app-private external directory on whichever volume is selected. */
    @Binds
    @Singleton
    fun bindDownloadStorage(impl: FileDownloadStorage): DownloadStorage

    /** Binds volume enumeration to the platform; the JVM tests substitute temporary directories. */
    @Binds
    @Singleton
    fun bindStorageVolumeProvider(impl: AndroidStorageVolumeProvider): StorageVolumeProvider

    /**
     * Binds the audio-sidecar strip stage to Media3's `Transformer`.
     *
     * An interface for the queue's sake: its unit tests must be able to run the whole AUDIO path —
     * fetch, strip, delete the fetch file — without a `Looper`, a muxer or a device.
     */
    @Binds
    @Singleton
    fun bindAudioSidecarExtractor(impl: TransformerAudioSidecarExtractor): AudioSidecarExtractor

    /** Binds the queue's scheduling to WorkManager. */
    @Binds
    @Singleton
    fun bindDownloadScheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler
}

/** Provides the HTTP client the download engine uses. */
@Module
@InstallIn(SingletonComponent::class)
internal object DownloadHttpModule {
    /**
     * A client of its own, not the SDK's.
     *
     * Two settings make it a *download* client rather than an API client: no read timeout, because
     * a healthy multi-gigabyte transfer legitimately holds one response open for an hour; and
     * redirects followed, because `/Items/{id}/Download` can redirect to a storage backend.
     * Sharing the SDK's client would mean either of those settings leaking into every API call.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * `FileDownloader` depends on the narrower [Call.Factory] rather than on `OkHttpClient`, which
     * is what lets its unit tests hand it a canned response with no server involved.
     *
     * Qualified for the same reason the client it wraps is (audit ARCH-06): an unqualified
     * `Call.Factory` binding would be the *only* one in the graph, so any future unqualified
     * `@Inject` would silently receive this no-timeout download client instead of failing to
     * compile.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadCallFactory(
        @DownloadHttpClient client: OkHttpClient,
    ): Call.Factory = client

    private const val CONNECT_TIMEOUT_SECONDS = 30L
}
