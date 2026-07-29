package dev.jellyfinnative.data.downloads.storage

import android.os.StatFs
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where downloaded files live, and the only thing in the pipeline that knows it (docs/PLAN.md,
 * "Download pipeline" → Storage).
 *
 * The plan's default is `getExternalFilesDir(null)/downloads` [D] — app-private external storage,
 * which needs no runtime permission, is wiped on uninstall (so a removed app leaves no gigabytes
 * behind) and is excluded from the media scanner.
 *
 * The root is no longer fixed: `StorageLocationManager` resolves it from the volume the user picked
 * in Settings, which may be an SD card (DECISIONS.md 2026-07-29, "the storage location picker ships
 * now, backed by secondary volumes"). It stays a plain `java.io.File` on every volume, so this
 * interface is unchanged by that; the SAF-tree backend the plan also describes is still the reason
 * the interface exists, and is still deferred.
 */
interface DownloadStorage {
    /** Absolute path of the storage root, or `null` when no external volume is mounted. */
    val rootPath: String?

    /** Creates (if needed) and returns the directory for one item. */
    fun prepareItemDirectory(directoryName: String): File

    /** The file handle for one planned file. Does not create anything. */
    fun resolve(
        directoryName: String,
        fileName: String,
    ): File

    /**
     * Removes an item's directory and everything in it.
     *
     * @return how many bytes were actually freed — what the Downloads screen reports after a
     *   delete, and what the milestone's "delete frees bytes" check measures.
     */
    fun deleteItemDirectory(directoryName: String): Long

    /** Bytes currently occupied by the downloads root, walked from disk rather than from Room. */
    fun usedBytes(): Long

    /** Bytes still writable on the volume the root lives on. */
    fun availableBytes(): Long
}

/**
 * [DownloadStorage] on plain `java.io.File`, rooted at `<chosen volume>/downloads`.
 *
 * Every method is defensive to the point of being boring: an unmounted volume, a directory that
 * cannot be created, a file that vanished between two calls. None of these may throw, because they
 * all run inside the download worker, where an exception is the difference between "this one item
 * failed" and "the whole queue stopped".
 *
 * Which volume that is comes from [StorageLocationManager] rather than from the `Context` directly,
 * which is the whole of what makes the location configurable — every path in the pipeline is built
 * from this class, so there is exactly one place the root has to change.
 */
@Singleton
class FileDownloadStorage
    @Inject
    constructor(
        private val locations: StorageLocationManager,
    ) : DownloadStorage {
        private fun root(): File? =
            locations.activeRoot()?.also { directory ->
                if (!directory.exists() && !directory.mkdirs()) {
                    Timber.w("Could not create the downloads root at %s", directory)
                }
            }

        override val rootPath: String? get() = root()?.absolutePath

        override fun prepareItemDirectory(directoryName: String): File {
            val root = root() ?: error("No external storage volume is available for downloads")
            val directory = File(root, directoryName)
            if (!directory.exists() && !directory.mkdirs()) {
                error("Could not create the download directory $directoryName")
            }
            return directory
        }

        override fun resolve(
            directoryName: String,
            fileName: String,
        ): File {
            val root = root() ?: error("No external storage volume is available for downloads")
            return File(File(root, directoryName), fileName)
        }

        override fun deleteItemDirectory(directoryName: String): Long {
            val root = root() ?: return 0L
            val directory = File(root, directoryName)
            if (!directory.exists()) return 0L

            val freed = directory.sizeRecursively()
            if (!directory.deleteRecursively()) {
                Timber.w("Could not fully delete %s; %d bytes may remain", directory, freed)
                // Report what actually went away rather than what we hoped would.
                return (freed - directory.sizeRecursively()).coerceAtLeast(0L)
            }
            return freed
        }

        override fun usedBytes(): Long = root()?.sizeRecursively() ?: 0L

        override fun availableBytes(): Long {
            val root = root() ?: return 0L
            return runCatching { StatFs(root.absolutePath).availableBytes }
                .onFailure { Timber.w(it, "Could not stat the downloads volume") }
                .getOrDefault(0L)
        }
    }

/**
 * Total size of a file, or of every file underneath a directory.
 *
 * `walkBottomUp` rather than `listFiles` recursion so a deep tree cannot blow the stack, and
 * `runCatching` because a file the queue is writing can disappear between the walk and the `length`
 * call.
 */
internal fun File.sizeRecursively(): Long =
    runCatching {
        if (isFile) {
            length()
        } else {
            walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }
    }.getOrDefault(0L)
