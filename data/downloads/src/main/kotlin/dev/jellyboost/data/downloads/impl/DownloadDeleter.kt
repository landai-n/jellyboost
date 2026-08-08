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
 * The delete cascade (docs/PLAN.md, "Download pipeline" → Delete cascade).
 *
 * Deleting a download is four steps, and skipping any of them leaves the app lying to the user:
 *
 * 1. **the download rows**, guarded — [DownloadDao.deleteUnlessRunnable]. `download_files` follows
 *    through the foreign key.
 * 2. **orphaned `ItemEntity(source = DOWNLOAD)` rows**, *including parents* — but only the parents
 *    no surviving download still needs. Deleting one episode of a show must not blank the series
 *    page its remaining episodes still open from.
 * 3. **the local user-data row**, unless it is still `toBeSynced`: a synced row is pure cache, a
 *    pending one is the only copy of a change the server has not seen.
 * 4. **the files**, for the rows that were actually removed.
 *
 * ### Why the row goes before its bytes, and why all the Room work is one transaction
 * The plan's cascade reads "delete files + dir → DB rows", and until audit CORR-1 that is what this
 * did. What it cost was the ability to *change its mind*: the row was read, the directory was
 * unlinked, and only then was the row deleted — so a download re-enqueued in between (see
 * [DownloadDao.deleteUnlessRunnable] for the five-second window that makes that ordinary) had its
 * files pulled out from under it, or was deleted outright. The guard has to be the **first**
 * destructive act or it guards nothing, and a guard that is a `DELETE` is a guard the database
 * enforces rather than one this class hopes for — the drain's `markDownloadingIfRunnable` pattern.
 *
 * Turning the order around costs one thing: a process death between the row delete and the unlink
 * leaves an item directory nothing points at. That is precisely what `OrphanSweeper` collects at
 * the head of every drain (audit 2026-07, STAB-04), and it had to exist anyway, because a
 * cancellation landing mid-file used to *recreate* the directory the old order had just removed.
 * So the failure the old order protected against is covered, and the one the new order prevents —
 * a silently vanished download — was not.
 *
 * The Room half (guarded deletes, prune, user-data sweep) runs inside one
 * [TransactionRunner.inTransaction] block. Beyond making the batch atomic, that is what closes
 * audit CORR-4: [pruneOrphanedItems] reads `allItemIds()` and then deletes every `DOWNLOAD` item
 * row outside that set, so an enqueue committing between the two would have its freshly written
 * metadata pruned — and the drain that later picks up the row fails with
 * `MissingMetadataException`, permanently. `DownloadEnqueuer` writes its metadata and its rows in
 * one transaction for the other half of the same handshake. The file deletes stay *outside* it:
 * unlinking gigabytes is not database work, and holding a write transaction across it would block
 * every other writer for the duration.
 *
 * Cancelling an in-flight job is the caller's job (`DownloadRepositoryImpl` stops the queue first),
 * because only it knows whether the item being deleted is the one currently transferring. Claiming
 * the targets — `DownloadDao.demoteRunnable(ids, CANCELLED, now)` — is the caller's job too, and it
 * is what [deleteAll]'s guard re-checks; a caller that skips it will find rows it asked to delete
 * still there.
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
        /**
         * Removes one download completely.
         *
         * @return bytes actually freed on disk — what the Downloads screen reports, and what the
         *   milestone's "delete frees bytes" check measures.
         */
        suspend fun delete(itemId: UUID): Long = deleteAll(listOf(itemId))

        /**
         * Removes several downloads, running the metadata prune **once** for the whole batch.
         *
         * The batch shape is not a convenience: *Cancel all* used to run the full cascade per row,
         * and each cascade's prune re-read every surviving download's whole `ItemEntity` — a
         * multi-tens-of-KB `BaseItemDto` blob apiece — making the operation O(deleted × remaining)
         * in blob reads, with the UI waiting on it (audit DL-05).
         *
         * @return total bytes actually freed on disk — nothing for a row this cascade no longer
         *   owns, since its files are not this cascade's to unlink.
         */
        suspend fun deleteAll(itemIds: List<UUID>): Long {
            val removed = transactionRunner.inTransaction { removeRows(itemIds) }
            return removed.sumOf(::freeFilesOf)
        }

        /**
         * The database half: the guarded row deletes, the prune and the user-data sweep, in one
         * transaction.
         *
         * @return the rows that were actually removed — the ones whose files this call now owns.
         *   A row missing from the answer was either never there or had been put back in the queue
         *   since the cascade claimed it, and in both cases its directory belongs to somebody else.
         */
        private suspend fun removeRows(itemIds: List<UUID>): List<DownloadEntity> {
            val removed = itemIds.mapNotNull { itemId -> removeRow(itemId) }

            if (removed.isNotEmpty()) {
                pruneOrphanedItems()
                removed.forEach { downloadDao.deleteSyncedUserData(it.itemId) }
            }
            return removed
        }

        /**
         * Deletes one row if this cascade still owns it.
         *
         * @return the row as it was, or `null` when there was none — or when it is runnable again,
         *   which means the user asked for this download a second time and their tap outranks a
         *   cancel they have already moved on from.
         */
        private suspend fun removeRow(itemId: UUID): DownloadEntity? {
            val download = downloadDao.get(itemId) ?: return null
            if (downloadDao.deleteUnlessRunnable(itemId) == 0) {
                Timber.i("%s was queued again while being deleted; leaving it alone", download.itemName)
                return null
            }
            return download
        }

        /** Unlinks one removed download's directory. @return bytes actually freed. */
        private fun freeFilesOf(download: DownloadEntity): Long =
            runCatching { storage.deleteItemDirectory(download.directoryName) }
                .onFailure { Timber.w(it, "Could not delete the files of %s", download.itemName) }
                .getOrDefault(0L)

        /**
         * Drops every `DOWNLOAD`-sourced item row that no remaining download needs.
         *
         * The surviving set is computed rather than guessed: it is the remaining downloads plus,
         * for each of them, its series and season. That is exactly the set the offline read path
         * walks — an episode's detail page reaches its season, which reaches its series. The walk
         * reads [ItemDao.getParentRefs], a projection without the `dto` blob: the prune needs only
         * the links, never the metadata itself (audit DL-05).
         *
         * Run after every batch rather than only on the last one, so a half-cleaned cache cannot
         * accumulate over a session of deletions — and inside [deleteAll]'s transaction, because
         * the read that computes the surviving set and the delete that acts on it must not have an
         * enqueue between them (audit CORR-4; see this class's documentation).
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
                    .flatMap { listOfNotNull(it.seriesId, it.seasonId, it.parentId) }

            val keep = (remaining + parents).distinct()
            val pruned = itemDao.deleteDownloadsNotIn(keep, ItemSource.DOWNLOAD)
            if (pruned > 0) Timber.d("Pruned %d orphaned downloaded item rows", pruned)
        }
    }
