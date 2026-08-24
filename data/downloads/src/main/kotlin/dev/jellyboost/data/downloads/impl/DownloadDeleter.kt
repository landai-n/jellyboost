package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.data.downloads.storage.DownloadStorage
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The delete cascade: four steps, and skipping any of them leaves the app lying to the user.
 *
 * 1. **the download rows**, guarded — [DownloadDao.deleteUnlessRunnable]; `download_files` follows
 *    through the foreign key.
 * 2. **orphaned `ItemEntity(source = DOWNLOAD)` rows**, including only the parents no surviving
 *    download still needs.
 * 3. **the local user-data row**, unless it still owes the server a change (`toBeSynced`).
 * 4. **the files**, for the rows that were actually removed.
 *
 * The row goes before its bytes because the guard has to be the **first** destructive act or it
 * guards nothing: a download re-enqueued in between would have its files pulled out from under it.
 * The cost is that a process death between the row delete and the unlink leaves an item directory
 * nothing points at — which `OrphanSweeper` collects at the head of every drain, and has to exist
 * anyway because a cancellation landing mid-file can recreate a directory the cascade just removed.
 *
 * The Room half runs inside one [TransactionRunner.inTransaction] block: [pruneOrphanedItems] reads
 * `allItemIds()` and then deletes every `DOWNLOAD` item row outside that set, so an enqueue
 * committing between the two would have its freshly written metadata pruned — and the drain that
 * later picks up the row fails *permanently* with `MissingMetadataException`. The file deletes stay
 * **outside** it: holding a write transaction across gigabytes of unlinking would block every writer.
 *
 * Stopping an in-flight job, and claiming the targets with
 * `DownloadDao.demoteRunnable(ids, CANCELLED, now)`, are the caller's job — [deleteAll]'s guard
 * re-checks that claim, so a caller which skips it will find rows it asked to delete still there.
 */
@Singleton
internal class DownloadDeleter
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val storage: DownloadStorage,
        private val transactionRunner: TransactionRunner,
    ) {
        suspend fun delete(itemId: UUID): Long = deleteAll(listOf(itemId))

        /**
         * Removes several downloads, running the metadata prune **once** for the whole batch: per row
         * it would re-read every surviving download's whole `BaseItemDto` blob once per deleted row,
         * O(deleted × remaining) in blob reads with the UI waiting on it.
         *
         * @return total bytes actually freed on disk — nothing for a row this cascade no longer owns,
         *   since its files are not this cascade's to unlink.
         */
        suspend fun deleteAll(itemIds: List<UUID>): Long {
            val removed = transactionRunner.inTransaction { removeRows(itemIds) }
            return removed.sumOf(::freeFilesOf)
        }

        /**
         * @return the rows that were actually removed — the ones whose files this call now owns. A row
         *   missing from the answer was either never there or had been put back in the queue since the
         *   cascade claimed it, and in both cases its directory belongs to somebody else.
         */
        private suspend fun removeRows(itemIds: List<UUID>): List<DownloadEntity> {
            // One read for the whole batch; the *deletes* stay per row because each one's return
            // value is this cascade's claim check.
            val targets = downloadDao.getAll(itemIds)
            val removed = targets.filter { removeRow(it) }

            if (removed.isNotEmpty()) {
                pruneOrphanedItems()
                downloadDao.deleteSyncedUserData(removed.map { it.itemId })
            }
            return removed
        }

        /**
         * @return `false` when the row is runnable again — the user asked for this download a second
         *   time, and their tap outranks a cancel they have already moved on from.
         */
        private suspend fun removeRow(download: DownloadEntity): Boolean {
            if (downloadDao.deleteUnlessRunnable(download.itemId) == 0) {
                Timber.i("%s was queued again while being deleted; leaving it alone", download.itemName)
                return false
            }
            return true
        }

        /** Unlinks one removed download's directory. @return bytes actually freed. */
        private fun freeFilesOf(download: DownloadEntity): Long =
            runCatching { storage.deleteItemDirectory(download.directoryName) }
                .onFailure { Timber.w(it, "Could not delete the files of %s", download.itemName) }
                .getOrDefault(0L)

        /**
         * Drops every `DOWNLOAD`-sourced item row that no remaining download needs. The surviving set
         * is the remaining downloads plus their parents — series and season for an episode, album and
         * album artist for a track — which is exactly the set the offline read path walks.
         * [ItemDao.getParentRefs] is a projection without the `dto` blob: the prune needs the links only.
         *
         * Inside [deleteAll]'s transaction, because the read that computes the surviving set and the
         * delete that acts on it must not have an enqueue between them.
         */
        private suspend fun pruneOrphanedItems() {
            val remaining = downloadDao.allItemIds()
            if (remaining.isEmpty()) {
                itemDao.deleteDownloadsNotIn(emptyList(), ItemSource.DOWNLOAD)
                return
            }

            val parents =
                itemDao
                    .getParentRefs(remaining)
                    .flatMap {
                        listOfNotNull(it.seriesId, it.seasonId, it.parentId, it.albumId, it.albumArtistId)
                    }

            val keep = (remaining + parents).distinct()
            val pruned = itemDao.deleteDownloadsNotIn(keep, ItemSource.DOWNLOAD)
            if (pruned > 0) Timber.d("Pruned %d orphaned downloaded item rows", pruned)
        }
    }
