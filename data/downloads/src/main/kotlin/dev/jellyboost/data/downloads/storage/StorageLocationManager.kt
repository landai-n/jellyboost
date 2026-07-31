package dev.jellyboost.data.downloads.storage

import dev.jellyboost.core.datastore.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which volume the download root sits on (docs/PLAN.md, ":data:downloads" → `StorageLocationManager`).
 *
 * It owns exactly one rule, and the rule is a fallback: **the stored choice if that volume is
 * mounted, the primary volume otherwise.** A user who picks the SD card and then takes it out gets
 * an app that still works — new downloads land on internal storage and the settings screen says so
 * — rather than an app that writes nowhere, or worse, writes to whatever volume happens to occupy
 * the old index now.
 *
 * ### Why a cache, and why it may be read synchronously
 * [DownloadStorage] is not a suspending interface: `rootPath`, `resolve` and `deleteItemDirectory`
 * are called from inside the download worker and the delete cascade, several of them per file.
 * Making the root depend on a `Flow` would mean either suspending that whole surface — a change
 * that reaches the queue, the planner, the deleter and their tests — or resolving the preference on
 * every call. Instead the chosen id is read **once per process** and kept: it can only change
 * through [select], which updates the cache with it, so there is no window where the two disagree.
 * The one blocking read happens on whichever IO thread first asks for the root.
 */
@Singleton
class StorageLocationManager
    @Inject
    constructor(
        private val volumeProvider: StorageVolumeProvider,
        private val preferences: AppPreferences,
    ) {
        @Volatile private var cachedVolumeId: String? = null

        @Volatile private var cacheLoaded = false

        private val cacheLock = Any()

        /** The stored choice, `null` while the default holds — what the settings screen observes. */
        val selectedVolumeId: Flow<String?> get() = preferences.downloadStorageVolumeId

        /**
         * The volumes available now, and which of them downloads are actually written to.
         *
         * Pure with respect to [selectedVolumeId]: callers pass the id they read from the flow, so
         * a projection never has to worry about the cache below being a frame behind.
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
         * The downloads root on the active volume, or `null` when no volume is mounted at all.
         *
         * Always `<app-specific dir>/downloads`, on whichever volume is active — so the primary
         * volume resolves to exactly the path every download before this picker existed was written
         * to, and no migration is needed for an install that never changes the setting.
         */
        fun activeRoot(): File? = activeVolume()?.let { File(it.directory, DOWNLOADS_DIRECTORY) }

        /** The volume being written to right now — the fallback, when the chosen one is not there. */
        fun activeVolume(): DownloadVolume? = resolve(cachedVolumeId()).active

        /**
         * Points future downloads at [volumeId].
         *
         * Deliberately dumb: it does not check whether downloads already exist, and does not move
         * anything. Both belong to `DownloadRepository`, which owns the plan's v1 policy ("location
         * change only when no downloads exist, or delete all and switch") and the delete cascade
         * that implements it.
         */
        suspend fun select(volumeId: String) {
            preferences.setDownloadStorageVolumeId(volumeId)
            synchronized(cacheLock) {
                cachedVolumeId = volumeId
                cacheLoaded = true
            }
        }

        private fun cachedVolumeId(): String? {
            if (!cacheLoaded) {
                synchronized(cacheLock) {
                    if (!cacheLoaded) {
                        cachedVolumeId = runBlocking { preferences.downloadStorageVolumeId.first() }
                        cacheLoaded = true
                    }
                }
            }
            return cachedVolumeId
        }

        private companion object {
            /** Sub-directory of the volume's app-specific directory, per docs/PLAN.md. */
            const val DOWNLOADS_DIRECTORY = "downloads"
        }
    }

/**
 * Where downloads can go and where they currently go.
 *
 * @property active `null` only when no external volume is mounted at all — an unusual state the
 *   storage backend already had to handle before any of this existed.
 * @property selectionMissing `true` when the user's chosen volume is not mounted and [active] is
 *   therefore a fallback. The settings screen says so out loud; silently writing somewhere else is
 *   how a user ends up wondering where twelve gigabytes went.
 */
data class StorageSelection(
    val volumes: List<DownloadVolume>,
    val active: DownloadVolume?,
    val selectionMissing: Boolean,
)
