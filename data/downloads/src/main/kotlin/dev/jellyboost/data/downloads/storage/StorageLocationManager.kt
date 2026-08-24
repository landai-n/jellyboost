package dev.jellyboost.data.downloads.storage

import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.datastore.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which volume the download root sits on. One rule, and it is a fallback: **the stored choice if that
 * volume is mounted, the primary volume otherwise** — a user who picks the SD card and then takes it
 * out gets an app that still works, rather than one writing to whatever volume occupies the old index.
 *
 * [DownloadStorage] is not a suspending interface, so the chosen id is read **once per process** and
 * kept; it can only change through [select], which updates the cache with it, so there is no window
 * where the two disagree. Nothing blocks to fill it: a `runBlocking` under a non-suspending property
 * would put a DataStore read on whatever thread reaches [DownloadStorage.rootPath].
 *
 * An unseeded read answers `null`, which is the documented default rather than a failure mode: `null`
 * means "no volume chosen" and [resolve] answers it with the primary volume, the path every download
 * before this picker existed used. The worst a read in the seeding window can do is resolve the
 * primary volume for an install that chose an SD card, and `DownloadQueue.requireStableRoot` already
 * refuses to write a file whose row was planned against a different root.
 */
@Singleton
internal class StorageLocationManager
    @Inject
    constructor(
        private val volumeProvider: StorageVolumeProvider,
        private val preferences: AppPreferences,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        @Volatile private var cachedVolumeId: String? = null

        @Volatile private var cacheLoaded = false

        private val cacheLock = Any()

        init {
            // Fire-and-forget: nothing waits for it, and everything that reads before it lands gets
            // the primary volume, which is what `null` has always meant here.
            appScope.launch {
                val stored =
                    runCatchingUnlessCancelled { preferences.downloadStorageVolumeId.first() }
                        .onFailure { Timber.w(it, "Could not read the stored download volume; using the default") }
                        .getOrNull()
                synchronized(cacheLock) {
                    // A `select()` that landed first is the newer answer and must not be overwritten.
                    if (!cacheLoaded) {
                        cachedVolumeId = stored
                        cacheLoaded = true
                    }
                }
            }
        }

        /** The stored choice, `null` while the default holds — what the settings screen observes. */
        val selectedVolumeId: Flow<String?> get() = preferences.downloadStorageVolumeId

        /**
         * The volumes available now, and which of them downloads are actually written to. Pure with
         * respect to [selectedVolumeId]: callers pass the id they read from the flow, so a projection
         * never has to worry about the cache below being a frame behind.
         */
        fun resolve(selectedVolumeId: String?): StorageSelection {
            val volumes = volumeProvider.volumes()
            val chosen = selectedVolumeId?.let { id -> volumes.firstOrNull { it.id == id } }
            val active = chosen ?: volumes.firstOrNull { it.isPrimary } ?: volumes.firstOrNull()

            if (selectedVolumeId != null && chosen == null) {
                Timber.w("Download volume %s is not mounted; falling back to %s", selectedVolumeId, active?.id)
            }

            return StorageSelection(
                volumes = volumes,
                active = active,
                selectionMissing = selectedVolumeId != null && chosen == null,
            )
        }

        /**
         * The downloads root on the active volume, or `null` when no volume is mounted at all. Always
         * `<app-specific dir>/downloads`, so the primary volume resolves to exactly the path every
         * download before this picker existed was written to, and no migration is needed.
         */
        fun activeRoot(): File? = activeVolume()?.let { File(it.directory, DOWNLOADS_DIRECTORY) }

        /** The volume being written to right now — the fallback, when the chosen one is not there. */
        fun activeVolume(): DownloadVolume? = resolve(cachedVolumeId()).active

        /**
         * Points future downloads at [volumeId]. Deliberately dumb: it does not check whether downloads
         * already exist and does not move anything — both belong to `DownloadRepository`, which owns
         * the policy and the delete cascade that implements it.
         */
        suspend fun select(volumeId: String) {
            preferences.setDownloadStorageVolumeId(volumeId)
            synchronized(cacheLock) {
                cachedVolumeId = volumeId
                cacheLoaded = true
            }
        }

        /** `null` while the seed launched in `init` is still in flight, which [resolve] reads as the primary volume. */
        private fun cachedVolumeId(): String? = cachedVolumeId

        private companion object {
            /** Sub-directory of the volume's app-specific directory. */
            const val DOWNLOADS_DIRECTORY = "downloads"
        }
    }

/**
 * @property active `null` only when no external volume is mounted at all.
 * @property selectionMissing `true` when the user's chosen volume is not mounted and [active] is
 *   therefore a fallback. The settings screen says so out loud; silently writing somewhere else is how
 *   a user ends up wondering where twelve gigabytes went.
 */
internal data class StorageSelection(
    val volumes: List<DownloadVolume>,
    val active: DownloadVolume?,
    val selectionMissing: Boolean,
)
