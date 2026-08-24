package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.data.downloads.storage.DownloadStorage
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes item directories that no download row claims.
 *
 * Files outlive their row because WorkManager's cancellation is asynchronous: the transfer keeps
 * running until its next `ensureActive()`, and `FileDownloader` re-creates the item directory for
 * *every* file it opens, so a cancel landing inside the multi-minute media file recreates the
 * directory the cascade has just removed. Nothing points at those bytes afterwards — invisible in both
 * Downloads tabs, surviving every later delete, and counted by `usedBytes()`. Awaiting
 * `DownloadScheduler.stop()` closes most of that window; this closes the rest, plus every orphan a
 * process death left behind.
 *
 * Safe to delete what it finds because the root is app-private and written by this pipeline alone, and
 * the *names* are compared rather than the ids — the row that could resolve an id is exactly what is
 * missing. An unmounted volume lists nothing, so the sweep is a no-op instead of a wipe.
 */
@Singleton
internal class OrphanSweeper
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val storage: DownloadStorage,
    ) {
        /**
         * Best effort by construction: this runs at the head of a drain, and a queue that refuses to
         * download because a stale directory could not be removed would be the worse bug.
         *
         * @return bytes freed.
         */
        suspend fun sweep(): Long =
            try {
                val claimed = downloadDao.allDirectoryNames().toSet()
                val orphans = storage.itemDirectoryNames().filterNot { it in claimed }
                if (orphans.isEmpty()) {
                    0L
                } else {
                    val freed = orphans.sumOf(storage::deleteItemDirectory)
                    Timber.i("Swept %d orphaned download directories, freeing %d bytes", orphans.size, freed)
                    freed
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.w(error, "Could not sweep orphaned download directories")
                0L
            }
    }
