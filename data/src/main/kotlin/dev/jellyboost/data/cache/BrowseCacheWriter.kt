package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.userdata.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-through half of the browse cache. Four invariants, each of which has cost data before:
 *
 * 1. **A browse write never downgrades a download.** An existing [ItemSource.DOWNLOAD] row keeps its
 *    source (or a scroll past it makes it evictable, orphaning its files) and its original
 *    [ItemEntity.cachedAt] (which is what the offline "recently downloaded" rows order by).
 * 2. **Only a *full* read may replace a download's stored blob.** A lean list DTO written into
 *    [ItemEntity.dto] would wipe the rich blob `DownloadEnqueuer` stored; preserving it
 *    unconditionally would instead leave an already-gutted row unrepairable, since `getItem` is the
 *    only complete response. So the caller declares it with `full`, never sniffed from DTO shape —
 *    sniffing gets an item that genuinely has no overview wrong in the direction that loses data.
 * 3. **A server read refreshes `user_data` unless the row is `toBeSynced`.** Without the refresh,
 *    `UserDataRepositoryImpl.setPosition` pushes a stale full state back and un-watches an item
 *    watched on another client. A pending row is the only copy of an unaccepted change; reconciling
 *    is the sync worker's job. Filter and write share one transaction, or a local write landing
 *    between them is overwritten flag and all and `UserDataSyncWorker` never sees it again.
 * 4. **Read, merge and write are one transaction.** [mergeRows] stays pure so it is JVM-testable,
 *    but a `DOWNLOAD` upsert from `DownloadEnqueuer` landing between the read and the write would
 *    break invariant 1 — reachable by tapping Download while a season list write is in flight.
 *
 * [cacheItems]/[cacheViews] are fire-and-forget on the application scope: a failed cache write is a
 * logged warning, never a failed read. Tests inject a `TestScope` for determinism.
 */
@Singleton
internal class BrowseCacheWriter
    @Suppress(
        "LongParameterList",
    )
    @Inject
    constructor(
        private val itemDao: ItemDao,
        private val libraryViewDao: LibraryViewDao,
        private val userDataDao: UserDataDao,
        private val sessionRepository: SessionRepository,
        private val mapper: ItemEntityMapper,
        private val maintenance: BrowseCacheMaintenance,
        private val clock: Clock,
        private val transactionRunner: TransactionRunner,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * @param full `true` only for a response known to carry the **complete** field set — in
         *   practice `getItem` alone. The default is the safe answer: preserve the stored blob.
         */
        fun cacheItems(
            dtos: List<BaseItemDto>,
            full: Boolean = false,
        ) {
            if (dtos.isEmpty()) return
            scope.launch { writeItems(dtos, full) }
        }

        fun cacheViews(dtos: List<BaseItemDto>) {
            if (dtos.isEmpty()) return
            scope.launch { writeViews(dtos) }
        }

        /** Public so tests can await the write directly instead of racing the scope. */
        suspend fun writeItems(
            dtos: List<BaseItemDto>,
            full: Boolean = false,
        ) {
            val now = clock.instant()
            writeItemRows(dtos, now, full)
            refreshUserData(dtos, now)
            // Writing grows the table, so writing pays for bounding it. Throttled and off-scope —
            // nothing here waits for the sweep.
            maintenance.onWriteThrough()
        }

        /** One transaction: no concurrent `DOWNLOAD` upsert may land between the read and the write. */
        private suspend fun writeItemRows(
            dtos: List<BaseItemDto>,
            now: Instant,
            full: Boolean,
        ) {
            try {
                transactionRunner.inTransaction {
                    val existing = itemDao.getCacheKeys(dtos.map { it.id }).associateBy { it.id }

                    // Blobs are read only for download rows a lean write must protect; reading them
                    // for ordinary browse-cache rows is why `getCacheKeys` excludes the column.
                    val downloadIds =
                        if (full) {
                            emptyList()
                        } else {
                            existing.values.filter { it.source == ItemSource.DOWNLOAD }.map { it.id }
                        }
                    val richBlobs =
                        if (downloadIds.isEmpty()) {
                            emptyMap()
                        } else {
                            itemDao.getItems(downloadIds).associate { it.id to it.dto }
                        }

                    itemDao.upsert(mergeRows(dtos, existing, richBlobs, now))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not write %d items through to the browse cache", dtos.size)
            }
        }

        /**
         * Pure — no database access — so it stays a JVM unit test rather than a device one.
         *
         * @param existing keyed by id; a missing entry is a row that does not exist yet.
         * @param richBlobs blobs worth preserving, empty on a full write so the complete response
         *   wins and a previously gutted row is repaired.
         */
        internal fun mergeRows(
            dtos: List<BaseItemDto>,
            existing: Map<UUID, ItemCacheKey>,
            richBlobs: Map<UUID, String>,
            now: Instant,
        ): List<ItemEntity> =
            dtos.map { dto ->
                val row = mapper.toEntity(dto, ItemSource.BROWSE_CACHE, now)
                val previous = existing[dto.id]
                if (previous?.source == ItemSource.DOWNLOAD) {
                    // `revisedAt` is not carried over with `cachedAt`: it is the freshness key a
                    // metadata memo compares, and this write does replace the row. A preserved rich
                    // blob then costs one re-decode of identical bytes, which `distinctUntilChanged`
                    // absorbs — the opposite mistake, a memo holding a blob that is gone, does not
                    // resolve itself at all.
                    row.copy(
                        source = ItemSource.DOWNLOAD,
                        cachedAt = previous.cachedAt,
                        dto = richBlobs[dto.id] ?: row.dto,
                    )
                } else {
                    row
                }
            }

        /** Invariant 3. The pending-row filter and the write it guards must stay one transaction. */
        private suspend fun refreshUserData(
            dtos: List<BaseItemDto>,
            now: Instant,
        ) {
            val userId = currentUserId() ?: return
            // A response without user data says nothing about the user's state — it must never be
            // read as "unwatched, not favourite".
            val fromServer = dtos.mapNotNull { dto -> dto.userData?.let { dto.id to it } }
            if (fromServer.isEmpty()) return

            try {
                transactionRunner.inTransaction {
                    val pending = userDataDao.getPendingSyncIds(fromServer.map { it.first }, userId).toSet()
                    val rows =
                        fromServer
                            .filterNot { (itemId, _) -> itemId in pending }
                            .map { (itemId, userData) -> userData.toEntity(itemId, userId, now) }
                    if (rows.isNotEmpty()) userDataDao.upsertAll(rows)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not refresh %d user-data rows from the server", fromServer.size)
            }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId

        suspend fun writeViews(dtos: List<BaseItemDto>) {
            try {
                val now = clock.instant()
                val rows = dtos.mapIndexedNotNull { index, dto -> mapper.toEntity(dto, index, now) }
                if (rows.isEmpty()) return

                libraryViewDao.upsert(rows)
                // Guarded on the non-empty result above, so a filtered-to-nothing response cannot
                // wipe the table.
                libraryViewDao.deleteExcept(rows.map { it.id })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SQLiteException) {
                Timber.w(error, "Could not write the library list through to the cache")
            }
        }
    }
