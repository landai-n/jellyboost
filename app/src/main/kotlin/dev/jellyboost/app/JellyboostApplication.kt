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
 * Owns Hilt's object graph, provides the WorkManager configuration used by the download and
 * user-data sync workers, and configures the one Coil image loader every `JellyfinAsyncImage` in
 * the app draws through.
 *
 * ### The startup contract
 * The five process-lifetime collaborators below are injected as [Lazy] and started from a coroutine
 * on the application scope, **not** as `lateinit` fields resolved inside `super.onCreate()`. Held
 * eagerly, the member injection built the entire singleton graph on the main thread before the
 * first frame: `ApiClientProvider` → a blocking `SharedPreferences` XML read and, on first run, a
 * synchronous `commit()` fsync, plus a `Settings.Global` binder call and Ktor/OkHttp construction.
 *
 * What the deferral does and does not promise:
 * - **Ordering between the five is not one.** None of them observes another; each is an idempotent
 *   `start()` over a flow or a lifecycle, and each is written to be correct whenever it happens to
 *   run. Three of them wait on a connectivity edge that has not been published yet at this point in
 *   any case.
 * - **Nothing is lost by starting late.** `ProcessLifecycleOwner` replays the current state to an
 *   observer registered after `ON_START`, and the three connectivity watchers collect a `StateFlow`,
 *   which replays its current value. A missed *edge* is not possible because there is no edge until
 *   the monitor these all hang off is itself running.
 * - **[SyncPlayPresenceCoordinator.start] is main-thread-only** (`ProcessLifecycleOwner` requires
 *   it), so it alone hops back. Its construction, like the other four, stays off the main thread.
 * - **[workerFactory] stays eager**, because it is not ours to defer: `Configuration.Provider` is a
 *   platform contract WorkManager may read on its own initialization, and the factory is a map of
 *   `Provider`s — it builds no worker until one is actually run.
 */
@HiltAndroidApp
class JellyboostApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** The process-lifetime scope the five collaborators below are started from. */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /** For the one `start()` that must run on the main thread; see the class KDoc. */
    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    /**
     * Watches the connection so user-data changes made offline reach the server.
     *
     * Injected here rather than from a ViewModel because it has to run whether or not any screen is
     * showing — a device that comes back online with the app in the background is the case that
     * matters, and it is also what makes a pending row survive an app kill.
     */
    @Inject
    lateinit var userDataSyncTrigger: Lazy<UserDataSyncTrigger>

    /**
     * Keeps every downloaded item's cached metadata in step with the server's, whenever online.
     *
     * A standing sync, not a migration: a download's metadata is written once at enqueue time and
     * would otherwise never pick up a retitle, an artwork change or a corrected overview again.
     * Injected alongside the sync trigger and for the same reason — it is worth nothing if it only
     * runs while a particular screen happens to be showing.
     */
    @Inject
    lateinit var downloadedMetadataRefresher: Lazy<DownloadedMetadataRefresher>

    /**
     * Expires the browse cache, so the `items` table stops growing for the life of the install.
     *
     * The counterpart of the refresher above — that one keeps downloaded metadata current, this one
     * throws the *browsed* metadata away once it is old enough to be worthless. Here rather than on
     * a screen for the plainest of the reasons: a sweep that only ran while some particular screen
     * was showing would be a sweep tied to the very activity that fills the table.
     */
    @Inject
    lateinit var browseCacheMaintenance: Lazy<BrowseCacheMaintenance>

    /**
     * Keeps a SyncPlay group alive while the app is off screen, and takes one back on return.
     *
     * Here for the same reason as the two above, and more sharply: the whole failure it fixes
     * happens while no screen exists — the platform cuts a backgrounded app's network, the group is
     * lost, and the user is looking at jellyfin-web on the other half of the same tablet.
     */
    @Inject
    lateinit var syncPlayPresenceCoordinator: Lazy<SyncPlayPresenceCoordinator>

    /**
     * Watches for a Cast session, and keeps reporting one after the player screen is gone.
     *
     * Here for the same reason as the three above, and it is the sharper case: a cast session is
     * started from the top bar, outlives whichever screen was open, and has to end with a stop
     * report and an encoder kill whether or not anything is on screen when the receiver disconnects.
     * The Cast *stack* is still brought up by `MainActivity` behind its Play-services guard — this
     * only subscribes, and on a device with no Cast stack it waits for a signal that never comes.
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
     * The app-wide Coil image loader.
     *
     * Coil 3 gives a hand-built loader **no** disk cache and no transition unless it is told to:
     * without them, every poster that scrolls out of the memory cache is re-fetched over the
     * network, and each one pops in. Both are felt on the home screen and in the library grid,
     * which are nothing but images.
     *
     * Sizes are deliberately modest: artwork is requested at fixed widths by
     * `SdkImageUrlFactory`, so the entries are small, and 25 % of the app's heap holds a screenful
     * of a grid several times over on the test tablet.
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

    /**
     * Builds and starts the five process-lifetime collaborators, off the cold-start path.
     *
     * The application scope is dispatched on IO, so `get()` — where the singleton graph is actually
     * built — happens on a background thread. See the class KDoc for what this ordering does and
     * does not guarantee.
     */
    private fun startBackgroundCollaborators() {
        applicationScope.launch {
            userDataSyncTrigger.get().start()
            downloadedMetadataRefresher.get().start()
            browseCacheMaintenance.get().start()
            castSessionCoordinator.get().start()

            // Built here, started there: `ProcessLifecycleOwner.addObserver` asserts the main
            // thread, and only that one call needs it.
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
