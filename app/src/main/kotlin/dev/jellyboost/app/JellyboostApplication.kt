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
import dagger.hilt.android.HiltAndroidApp
import dev.jellyboost.data.downloads.DownloadedMetadataRefresher
import dev.jellyboost.data.userdata.UserDataSyncTrigger
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.syncplay.presence.SyncPlayPresenceCoordinator
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Owns Hilt's object graph, provides the WorkManager configuration used by the download and
 * user-data sync workers (docs/PLAN.md, ":app"), and configures the one Coil image loader every
 * `JellyfinAsyncImage` in the app draws through.
 */
@HiltAndroidApp
class JellyboostApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Watches the connection so user-data changes made offline reach the server (M8).
     *
     * Injected here rather than from a ViewModel because it has to run whether or not any screen is
     * showing — a device that comes back online with the app in the background is the case that
     * matters, and it is also what makes a pending row survive an app kill.
     */
    @Inject
    lateinit var userDataSyncTrigger: UserDataSyncTrigger

    /**
     * Keeps every downloaded item's cached metadata in step with the server's, whenever online.
     *
     * A standing sync, not a migration: a download's metadata is written once at enqueue time and
     * would otherwise never pick up a retitle, an artwork change or a corrected overview again.
     * Injected alongside the sync trigger and for the same reason — it is worth nothing if it only
     * runs while a particular screen happens to be showing.
     */
    @Inject
    lateinit var downloadedMetadataRefresher: DownloadedMetadataRefresher

    /**
     * Keeps a SyncPlay group alive while the app is off screen, and takes one back on return
     * (DECISIONS.md 2026-07-31).
     *
     * Here for the same reason as the two above, and more sharply: the whole failure it fixes
     * happens while no screen exists — the platform cuts a backgrounded app's network, the group is
     * lost, and the user is looking at jellyfin-web on the other half of the same tablet.
     */
    @Inject
    lateinit var syncPlayPresenceCoordinator: SyncPlayPresenceCoordinator

    /**
     * Watches for a Cast session, and keeps reporting one after the player screen is gone (M12).
     *
     * Here for the same reason as the three above, and it is the sharper case: a cast session is
     * started from the top bar, outlives whichever screen was open, and has to end with a stop
     * report and an encoder kill whether or not anything is on screen when the receiver disconnects.
     * The Cast *stack* is still brought up by `MainActivity` behind its Play-services guard — this
     * only subscribes, and on a device with no Cast stack it waits for a signal that never comes.
     */
    @Inject
    lateinit var castSessionCoordinator: CastSessionCoordinator

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    /**
     * The app-wide Coil image loader.
     *
     * Coil 3 gives a hand-built loader **no** disk cache and no transition unless it is told to, and
     * the app had been running on the bare defaults: every poster that scrolled out of the memory
     * cache was re-fetched over the network, and each one popped in. Both are felt on the home
     * screen and in the library grid, which are nothing but images (POLISH.md, "media list
     * scrolling").
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
        userDataSyncTrigger.start()
        downloadedMetadataRefresher.start()
        syncPlayPresenceCoordinator.start()
        castSessionCoordinator.start()
    }

    private companion object {
        const val MEMORY_CACHE_HEAP_FRACTION = 0.25
        const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024
        const val CROSSFADE_MILLIS = 150
        const val IMAGE_CACHE_DIRECTORY = "image_cache"
    }
}
