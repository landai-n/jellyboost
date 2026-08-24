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
 * ### Why files can outlive their row
 * Deleting a download stops the queue and then unlinks the directory, but WorkManager's
 * cancellation is asynchronous: the transfer keeps running until its next `ensureActive()`, and
 * `FileDownloader` re-creates the item directory for *every* file it opens. A cancel landing inside
 * the multi-minute media file therefore recreates the directory the cascade has just removed, and
 * writes into it. Nothing points at those bytes afterwards — they are invisible in both Downloads
 * tabs, survive every later delete, and are counted by `usedBytes()`, so the storage header accuses
 * the user of space they cannot find.
 *
 * Awaiting the stop (`DownloadScheduler.stop()`) closes most of that window; this closes the rest,
 * plus every orphan a process death or a crash left behind before it existed.
 *
 * ### Why it is safe to delete what it finds
 * The root is app-private (`<volume>/downloads`), written by this pipeline and nothing else, and
 * the *names* are compared rather than the ids, because the row that could resolve an id is exactly
 * what is missing. An unmounted volume lists nothing, so the sweep is a no-op instead of a wipe.
 */
@Singleton
internal class OrphanSweeper
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val storage: DownloadStorage,
    ) {
        /**
         * Deletes every item directory with no row behind it.
         *
         * Best effort by construction: this runs at the head of a drain, and a queue that refuses
         * to download because a stale directory could not be removed would be a worse bug than the
         * one it is cleaning up after.
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
