package dev.jellyfinnative.data.downloads.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.data.downloads.DownloadApi
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.downloads.DownloadRepositoryImpl
import dev.jellyfinnative.data.downloads.SdkDownloadApi
import dev.jellyfinnative.data.downloads.plan.DownloadUrlFactory
import dev.jellyfinnative.data.downloads.plan.SdkDownloadUrlFactory
import dev.jellyfinnative.data.downloads.storage.AndroidStorageVolumeProvider
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import dev.jellyfinnative.data.downloads.storage.FileDownloadStorage
import dev.jellyfinnative.data.downloads.storage.StorageVolumeProvider
import dev.jellyfinnative.data.downloads.work.DownloadScheduler
import dev.jellyfinnative.data.downloads.work.WorkManagerDownloadScheduler
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
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

    /** Binds the queue's scheduling to WorkManager. */
    @Binds
    @Singleton
    fun bindDownloadScheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler
}

/** Marks the OkHttp client the download engine transfers on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

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
     */
    @Provides
    @Singleton
    fun provideDownloadCallFactory(
        @DownloadHttpClient client: OkHttpClient,
    ): Call.Factory = client

    private const val CONNECT_TIMEOUT_SECONDS = 30L
}
