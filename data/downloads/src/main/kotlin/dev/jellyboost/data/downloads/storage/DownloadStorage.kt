package dev.jellyboost.data.downloads.storage

import android.os.StatFs
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A storage root that is not usable *right now* — no volume mounted, a directory that could not be
 * created, or the active root moving underneath a transfer.
 *
 * A distinct type because the condition is transient by nature. As a bare `IllegalStateException` it
 * would fall through `toAppError()` to `AppError.Unknown`, which the failure classifier calls
 * PERMANENT, and one unmounted volume would then mark every queued row ERROR within seconds;
 * `DownloadFailureClassifier` recognises this type as TRANSIENT instead.
 */
internal class StorageUnavailableException(
    message: String,
) : IllegalStateException(message)

/**
 * Where downloaded files live, and the only thing in the pipeline that knows it.
 *
 * The default is `getExternalFilesDir(null)/downloads` — app-private external storage, which needs no
 * runtime permission, is wiped on uninstall and is excluded from the media scanner. The root is not
 * fixed: `StorageLocationManager` resolves it from the volume the user picked in Settings.
 */
internal interface DownloadStorage {
    /** Absolute path of the storage root, or `null` when no external volume is mounted. */
    val rootPath: String?

    fun prepareItemDirectory(directoryName: String): File

    /** The file handle for one planned file. Does not create anything. */
    fun resolve(
        directoryName: String,
        fileName: String,
    ): File

    /**
     * The item directories that currently exist under the root. An unmounted volume answers with an
     * empty list, which makes the orphan sweep a no-op rather than making everything look orphaned.
     */
    fun itemDirectoryNames(): List<String>

    /** @return how many bytes were actually freed. */
    fun deleteItemDirectory(directoryName: String): Long

    /** Bytes currently occupied by the downloads root, walked from disk rather than from Room. */
    fun usedBytes(): Long

    fun availableBytes(): Long
}

/**
 * [DownloadStorage] on plain `java.io.File`, rooted at `<chosen volume>/downloads`.
 *
 * Every method is defensive to the point of being boring — an unmounted volume, a directory that
 * cannot be created, a file that vanished between two calls — because they all run inside the download
 * worker, where an exception is the difference between one failed item and the whole queue stopping.
 */
@Singleton
internal class FileDownloadStorage
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
            val root = root() ?: throw StorageUnavailableException(NO_VOLUME_MESSAGE)
            val directory = File(root, directoryName)
            if (!directory.exists() && !directory.mkdirs()) {
                throw StorageUnavailableException("Could not create the download directory $directoryName")
            }
            return directory
        }

        override fun resolve(
            directoryName: String,
            fileName: String,
        ): File {
            val root = root() ?: throw StorageUnavailableException(NO_VOLUME_MESSAGE)
            return File(File(root, directoryName), fileName)
        }

        override fun itemDirectoryNames(): List<String> {
            val root = root() ?: return emptyList()
            // `listFiles` returns null on an I/O error as well as on "not a directory"; either way
            // the honest answer is "nothing found", never "everything is an orphan".
            return root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
        }

        @Suppress(
            "ReturnCount",
        )
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

        private companion object {
            const val NO_VOLUME_MESSAGE = "No external storage volume is available for downloads"
        }
    }

/**
 * Total size of a file, or of every file underneath a directory. `walkBottomUp` rather than
 * `listFiles` recursion so a deep tree cannot blow the stack, and `runCatching` because a file the
 * queue is writing can disappear between the walk and the `length` call.
 */
internal fun File.sizeRecursively(): Long =
    runCatching {
        if (isFile) {
            length()
        } else {
            walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }
    }.getOrDefault(0L)
