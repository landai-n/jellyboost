package dev.jellyboost.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.data.cache.BrowseCacheMaintenance
import dev.jellyboost.data.downloads.DownloadedMetadataRefresher
import dev.jellyboost.data.userdata.UserDataSyncTrigger
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.syncplay.presence.SyncPlayPresenceCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point.
 *
 * The five process-lifetime collaborators below are [Lazy] and started from a coroutine, **not**
 * `lateinit` fields: held eagerly, member injection built the whole singleton graph on the main
 * thread before the first frame (a blocking `SharedPreferences` read, a first-run `commit()` fsync,
 * a `Settings.Global` binder call, Ktor/OkHttp construction).
 *
 * Nothing depends on the order they start in, and nothing is lost by starting late — every one of
 * them replays from a `StateFlow` or `ProcessLifecycleOwner`, and there is no edge to miss until the
 * monitor they hang off is itself running. [workerFactory] stays eager because `Configuration
 * .Provider` is a platform contract WorkManager may read during its own initialization.
 */
@HiltAndroidApp
class JellyboostApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    // The four below are injected here rather than from a ViewModel because each has to run whether
    // or not a screen is showing.
    @Inject
    lateinit var userDataSyncTrigger: Lazy<UserDataSyncTrigger>

    /** A standing sync: a download's metadata is written once at enqueue time and never revisited. */
    @Inject
    lateinit var downloadedMetadataRefresher: Lazy<DownloadedMetadataRefresher>

    /** Expires the browse cache, so the `items` table stops growing for the life of the install. */
    @Inject
    lateinit var browseCacheMaintenance: Lazy<BrowseCacheMaintenance>

    /** Keeps a SyncPlay group alive while the app is off screen — the platform cuts a background app's network. */
    @Inject
    lateinit var syncPlayPresenceCoordinator: Lazy<SyncPlayPresenceCoordinator>

    /**
     * A cast session outlives the screen that started it and must still end with a stop report and an
     * encoder kill. Only subscribes — `MainActivity` brings the Cast stack up behind its
     * Play-services guard, and with no stack this waits for a signal that never comes.
     */
    @Inject
    lateinit var castSessionCoordinator: Lazy<CastSessionCoordinator>

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    /**
     * Coil 3 gives a hand-built loader **no** disk cache and no transition unless told to, so without
     * these every poster that scrolls out of the memory cache is re-fetched and pops in. The sizes
     * are modest because `SdkImageUrlFactory` requests artwork at fixed widths.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, MEMORY_CACHE_HEAP_FRACTION)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIRECTORY))
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }.crossfade(CROSSFADE_MILLIS)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        startBackgroundCollaborators()
    }

    /** The application scope is dispatched on IO, so `get()` builds the singleton graph off the main thread. */
    private fun startBackgroundCollaborators() {
        applicationScope.launch {
            userDataSyncTrigger.get().start()
            downloadedMetadataRefresher.get().start()
            browseCacheMaintenance.get().start()
            castSessionCoordinator.get().start()

            // `ProcessLifecycleOwner.addObserver` asserts the main thread; only that call needs it.
            val presence = syncPlayPresenceCoordinator.get()
            withContext(mainDispatcher) { presence.start() }
        }
    }

    private companion object {
        const val MEMORY_CACHE_HEAP_FRACTION = 0.25
        const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024
        const val CROSSFADE_MILLIS = 150
        const val IMAGE_CACHE_DIRECTORY = "image_cache"
    }
}
