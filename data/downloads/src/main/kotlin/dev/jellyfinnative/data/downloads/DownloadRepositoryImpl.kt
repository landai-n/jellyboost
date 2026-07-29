package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadProgress
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageUsage
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import dev.jellyfinnative.data.downloads.work.DownloadScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DownloadRepository] over Room, the storage backend and WorkManager.
 *
 * It owns the *decisions* — what pausing means for a running job, what cancel and delete have in
 * common, which item ids survive a delete — and delegates the mechanics to [DownloadEnqueuer],
 * [DownloadDeleter] and [DownloadScheduler], each of which is unit-tested on its own.
 *
 * Every mutation ends by (re)starting the queue. That is deliberate: the worker is idempotent, it
 * drains whatever Room says is pending, and making every path go through it means there is exactly
 * one place where downloading actually happens.
 */
@Singleton
class DownloadRepositoryImpl
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val itemMapper: ItemEntityMapper,
        private val enqueuer: DownloadEnqueuer,
        private val deleter: DownloadDeleter,
        private val scheduler: DownloadScheduler,
        private val storage: DownloadStorage,
        private val preferences: AppPreferences,
        private val sessionRepository: SessionRepository,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DownloadRepository {
        override fun observeStates(): Flow<Map<String, DownloadState>> =
            downloadDao
                .observeProgress()
                .map { rows -> rows.associate { it.itemId.toString() to it.toDownloadState() } }
                // Progress writes land up to twice a second; without this every card in the app
                // would recompose on each of them even when no badge actually changed.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)

        override fun observeDownloads(): Flow<List<DownloadItem>> =
            downloadDao
                .observeAll()
                .map(::toDownloadItems)
                // `observeAll` is a `@Transaction` over two tables, so one throttled progress
                // update re-emits it two or three times — once for the file row, once for the item
                // row. Only the emissions that actually changed something are worth a recomposition.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)

        override fun observeStorage(): Flow<StorageUsage> =
            downloadDao
                .observeProgress()
                .map {
                    StorageUsage(
                        usedBytes = storage.usedBytes(),
                        availableBytes = storage.availableBytes(),
                        rootPath = storage.rootPath,
                    )
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        override val wifiOnly: Flow<Boolean> get() = preferences.downloadOverWifiOnly

        override suspend fun setWifiOnly(enabled: Boolean) {
            preferences.setDownloadOverWifiOnly(enabled)
            // A running job keeps the constraints it was enqueued with, so the new rule only takes
            // effect on a restart — which is what makes the toggle feel immediate.
            scheduler.restart()
        }

        override suspend fun enqueue(itemId: String): AppResult<Unit> {
            val id = itemId.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(itemId))
            val userId = currentUserId() ?: return AppResult.Failure(AppError.Unauthorized())

            return when (val result = enqueuer.enqueue(id, userId)) {
                is AppResult.Failure -> result
                is AppResult.Success -> {
                    scheduler.ensureRunning()
                    AppResult.Success(Unit)
                }
            }
        }

        override suspend fun pause(itemId: String): AppResult<Unit> =
            mutate(itemId) { id ->
                downloadDao.setStatus(id, DownloadStatus.PAUSED, clock.instant())
                // Stop first, then restart: the running job may be on *this* item, and the only way
                // to interrupt it is to cancel the work. The restart picks up whatever is left.
                scheduler.stop()
                scheduler.ensureRunning()
            }

        override suspend fun resume(itemId: String): AppResult<Unit> =
            mutate(itemId) { id ->
                downloadDao.setStatus(id, DownloadStatus.QUEUED, clock.instant())
                scheduler.restart()
            }

        override suspend fun delete(itemId: String): AppResult<Long> {
            val id = itemId.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(itemId))

            return withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    // Stopping the queue before unlinking files means the downloader cannot be
                    // holding a handle to something we are about to remove.
                    scheduler.stop()
                    val freed = deleter.delete(id)
                    // Something else may still be queued behind the deleted item.
                    scheduler.ensureRunning()
                    AppResult.Success(freed)
                } catch (error: Exception) {
                    Timber.e(error, "Could not delete download %s", itemId)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        override suspend fun move(
            itemId: String,
            position: Int,
        ): AppResult<Unit> =
            mutate(itemId) { id ->
                val pending = downloadDao.pending().filter { it.itemId != id }
                val target = position.coerceIn(0, pending.size)
                val reordered = pending.toMutableList()
                downloadDao.get(id)?.let { reordered.add(target, it) }

                val now = clock.instant()
                // Renumbered from zero on every move: gaps left by completed or deleted items would
                // otherwise make "position" mean something different from "place in the list".
                reordered.forEachIndexed { index, row ->
                    if (row.queuePosition != index) downloadDao.setQueuePosition(row.itemId, index, now)
                }
            }

        /** The shared shape of every mutation: parse the id, run on IO, fold failures. */
        private suspend fun mutate(
            itemId: String,
            block: suspend (UUID) -> Unit,
        ): AppResult<Unit> {
            val id = itemId.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(itemId))

            return withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    block(id)
                    AppResult.Success(Unit)
                } catch (error: Exception) {
                    Timber.e(error, "Download operation failed for %s", itemId)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        /**
         * Joins download rows to the cached items they belong to.
         *
         * One `getItems` for the whole list rather than one per row: the Downloads screen re-reads
         * on every throttled progress write, and a per-row query would make that N round trips
         * through Room twice a second.
         */
        private suspend fun toDownloadItems(rows: List<DownloadWithFiles>): List<DownloadItem> {
            if (rows.isEmpty()) return emptyList()

            val items =
                itemDao
                    .getItems(rows.map { it.download.itemId })
                    .mapNotNull { entity -> itemMapper.toDomainOrNull(entity)?.let { entity.id to it } }
                    .toMap()

            return rows.map { row ->
                DownloadItem(
                    itemId = row.download.itemId.toString(),
                    title = row.download.itemName,
                    seriesName = row.download.seriesName,
                    status = row.download.status,
                    bytesDownloaded = row.download.bytesDownloaded,
                    bytesTotal = row.download.bytesTotal,
                    bytesOnDisk = row.bytesOnDisk,
                    queuePosition = row.download.queuePosition,
                    errorMessage = row.download.errorMessage,
                    item = items[row.download.itemId],
                )
            }
        }

        private fun currentUserId(): UUID? = (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId
    }

/** The badge state a progress row maps to. */
internal fun DownloadProgress.toDownloadState(): DownloadState = status.toDownloadState(bytesDownloaded, bytesTotal)

/**
 * The one place `DownloadStatus` (persistence) becomes `DownloadState` (UI).
 *
 * They are deliberately separate types: the UI's `Downloading` carries a progress fraction and has
 * no use for `CANCELLED`, which only ever exists between a cancel and the row's deletion.
 */
private fun DownloadStatus.toDownloadState(
    bytesDownloaded: Long,
    bytesTotal: Long,
): DownloadState =
    when (this) {
        DownloadStatus.QUEUED -> DownloadState.Queued
        DownloadStatus.DOWNLOADING ->
            DownloadState.Downloading(
                progress = if (bytesTotal <= 0L) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f),
            )

        DownloadStatus.PAUSED -> DownloadState.Paused
        DownloadStatus.DOWNLOADED -> DownloadState.Downloaded
        DownloadStatus.ERROR -> DownloadState.Failed
        DownloadStatus.CANCELLED -> DownloadState.NotDownloaded
    }

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
