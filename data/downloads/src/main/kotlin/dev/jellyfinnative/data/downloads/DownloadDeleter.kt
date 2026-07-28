package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The delete cascade (docs/PLAN.md, "Download pipeline" → Delete cascade).
 *
 * Deleting a download is five steps, and skipping any of them leaves the app lying to the user:
 *
 * 1. **files first.** The bytes are the thing the user asked to get rid of; if the process dies
 *    after this step the row is merely stale, whereas the other order leaves gigabytes on disk that
 *    nothing points at any more.
 * 2. **the download rows.** `download_files` follows through the foreign key.
 * 3. **orphaned `ItemEntity(source = DOWNLOAD)` rows**, *including parents* — but only the parents
 *    no surviving download still needs. Deleting one episode of a show must not blank the series
 *    page its remaining episodes still open from.
 * 4. **the local user-data row**, unless it is still `toBeSynced`: a synced row is pure cache, a
 *    pending one is the only copy of a change the server has not seen.
 *
 * Cancelling an in-flight job is the caller's job (`DownloadRepositoryImpl` stops the queue first),
 * because only it knows whether the item being deleted is the one currently transferring.
 */
@Singleton
class DownloadDeleter
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val storage: DownloadStorage,
    ) {
        /**
         * Removes one download completely.
         *
         * @return bytes actually freed on disk — what the Downloads screen reports, and what the
         *   milestone's "delete frees bytes" check measures.
         */
        suspend fun delete(itemId: UUID): Long {
            val download = downloadDao.get(itemId) ?: return 0L

            val freed =
                runCatching { storage.deleteItemDirectory(download.directoryName) }
                    .onFailure { Timber.w(it, "Could not delete the files of %s", download.itemName) }
                    .getOrDefault(0L)

            downloadDao.delete(itemId)
            pruneOrphanedItems()
            downloadDao.deleteSyncedUserData(itemId)

            return freed
        }

        /**
         * Drops every `DOWNLOAD`-sourced item row that no remaining download needs.
         *
         * The surviving set is computed rather than guessed: it is the remaining downloads plus,
         * for each of them, its series and season. That is exactly the set the offline read path
         * walks — an episode's detail page reaches its season, which reaches its series.
         *
         * Run after *every* delete rather than only on the last one, so a half-cleaned cache cannot
         * accumulate over a session of deletions.
         */
        private suspend fun pruneOrphanedItems() {
            val remaining = downloadDao.allItemIds()
            if (remaining.isEmpty()) {
                itemDao.deleteDownloadsNotIn(emptyList(), ItemSource.DOWNLOAD)
                return
            }

            val parents =
                itemDao
                    .getItems(remaining)
                    .flatMap { listOfNotNull(it.seriesId, it.seasonId, it.parentId) }

            val keep = (remaining + parents).distinct()
            val pruned = itemDao.deleteDownloadsNotIn(keep, ItemSource.DOWNLOAD)
            if (pruned > 0) Timber.d("Pruned %d orphaned downloaded item rows", pruned)
        }
    }
