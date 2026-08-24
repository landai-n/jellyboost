package dev.jellyboost.data.downloads.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One place downloaded files can be written to: always an **app-specific** directory — one entry of
 * `getExternalFilesDirs(null)` — and never an arbitrary folder the user browsed to. That is what keeps
 * the whole pipeline on plain `java.io.File`: no runtime permission, no persisted URI grant, no
 * `DocumentFile`, and the same wipe-on-uninstall behaviour on the SD card as on internal storage.
 *
 * @property id the token persisted in DataStore. `"primary"` for the built-in volume, otherwise the
 *   volume's UUID — deliberately not an index (they reorder when a card is pulled) and not a path
 *   (only stable while mounted).
 * @property description the system's own localised name for the volume, or `null` when the platform
 *   will not say — the UI then falls back to its own wording from [isRemovable].
 * @property directory the app-specific directory; the downloads root is a sub-directory of it, so a
 *   volume is never written to outside the app's own space.
 */
internal data class DownloadVolume(
    val id: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val description: String?,
    val directory: File,
) {
    /** Bytes still writable here — the figure the picker shows under each option. */
    val availableBytes: Long get() = runCatching { directory.usableSpace }.getOrDefault(0L)

    companion object {
        /** The built-in volume: the one every install starts on, and the fallback for all others. */
        const val PRIMARY_ID = "primary"
    }
}

/** An interface so `StorageLocationManager`'s resolution and fallback rules can be exercised on the JVM. */
internal interface StorageVolumeProvider {
    /** Every currently **mounted** volume, primary first; an ejected card is simply absent. */
    fun volumes(): List<DownloadVolume>
}

@Singleton
internal class AndroidStorageVolumeProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : StorageVolumeProvider {
        override fun volumes(): List<DownloadVolume> {
            // `getExternalFilesDirs` creates the directories as a side effect and can return null
            // entries for volumes that are present but not mounted — both are normal, neither is an
            // error, and a manufacturer ROM that throws here must not take the download queue down.
            val directories =
                runCatching { context.getExternalFilesDirs(null) }
                    .onFailure { Timber.w(it, "Could not enumerate external storage volumes") }
                    .getOrNull()
                    .orEmpty()

            return directories.withIndex().mapNotNull { (index, directory) ->
                directory
                    ?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                    // Index 0 is the primary volume by contract, and the index is read *before*
                    // unmounted entries are dropped so a gap cannot promote a card to "primary".
                    ?.toVolume(isPrimary = index == 0)
            }
        }

        private fun File.toVolume(isPrimary: Boolean): DownloadVolume {
            val volume =
                runCatching { context.getSystemService(StorageManager::class.java)?.getStorageVolume(this) }
                    .onFailure { Timber.w(it, "Could not describe the volume at %s", this) }
                    .getOrNull()
            val uuid = runCatching { volume?.uuid }.getOrNull()

            return DownloadVolume(
                // The path is a last resort: it identifies the volume correctly while it is
                // mounted, which is the only time an id has to match anything.
                id = if (isPrimary) DownloadVolume.PRIMARY_ID else uuid ?: absolutePath,
                isPrimary = isPrimary,
                isRemovable = !isPrimary && runCatching { volume?.isRemovable }.getOrNull() != false,
                description = runCatching { volume?.getDescription(context) }.getOrNull(),
                directory = this,
            )
        }
    }
