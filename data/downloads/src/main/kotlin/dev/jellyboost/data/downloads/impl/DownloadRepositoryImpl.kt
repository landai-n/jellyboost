package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.DownloadVolume
import dev.jellyboost.data.downloads.storage.StorageLocationManager
import dev.jellyboost.data.downloads.work.DownloadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [DownloadRepository] over Room, the storage backend and WorkManager.
 *
 * Every mutation ends by (re)starting the queue: the worker is idempotent and drains whatever Room
 * says is pending, so there is exactly one place where downloading actually happens.
 */
@Singleton
internal class DownloadRepositoryImpl
    @Suppress(
        "LongParameterList",
    )
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val itemMapper: ItemEntityMapper,
        private val enqueuer: DownloadEnqueuer,
        private val deleter: DownloadDeleter,
        private val scheduler: DownloadScheduler,
        private val storage: DownloadStorage,
        private val locations: StorageLocationManager,
        private val preferences: AppPreferences,
        private val sessionRepository: SessionRepository,
        private val clock: Clock,
        private val transactionRunner: TransactionRunner,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : DownloadRepository {
        /**
         * The one Room subscription every `DownloadBadge` reads from: four ViewModels call
         * [observeStates], and a cold flow each would be four independent `observeProgress()`
         * collectors re-running the same map over the same rows.
         *
         * `by lazy` because this is a constructor property — building the chain eagerly would call
         * `observeProgress()` in every unit test that never subscribes.
         */
        private val downloadStates: StateFlow<Map<String, DownloadState>> by lazy {
            downloadDao
                .observeProgress()
                .map { rows -> rows.associate { it.itemId.toString() to it.toDownloadState() } }
                // Progress writes land up to twice a second; without this every card in the app recomposes on each.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)
                .stateIn(
                    scope = appScope,
                    started = SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS),
                    initialValue = emptyMap(),
                )
        }

        override fun observeStates(): Flow<Map<String, DownloadState>> = downloadStates

        /**
         * The download list, with each row joined to the item it belongs to.
         *
         * The join is **memoised** per subscription: rebuilding it would `Json.decodeFromString` every
         * downloaded item's full `BaseItemDto` on every emission, two to six times a second for the
         * length of a transfer. The cache lives *inside* the flow, so it is touched only by this
         * flow's own collector — which is what makes it safe without a lock — and dies with the
         * subscription instead of holding every item's metadata for the life of the process.
         */
        override fun observeDownloads(): Flow<List<DownloadItem>> =
            flow {
                val metadata = DownloadMetadataCache(itemDao, itemMapper)
                val seasons = SeasonArtworkCache(itemDao, itemMapper)
                emitAll(downloadDao.observeAll().map { rows -> toDownloadItems(rows, metadata, seasons) })
            }
                // `observeAll` is a `@Transaction` over two tables, so one throttled progress update
                // re-emits it two or three times — once for the file row, once for the item row.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)

        /**
         * How much of the volume the downloads root occupies, and how much of it is left.
         *
         * `usedBytes()` is a `stat()` of *every* file under the root, so it must not be keyed on
         * `observeProgress` — which lands twice a second for the whole of a transfer. Three coarser
         * things move it instead: the *shape* of the download table, a slow [STORAGE_WALK_INTERVAL]
         * tick while anything is `DOWNLOADING` (the file grows without any row's shape changing), and
         * the selected volume — switching location with an empty queue changes no download row, so the
         * header would otherwise keep reporting the old volume's free space.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observeStorage(): Flow<StorageUsage> =
            combine(downloadShape, locations.selectedVolumeId) { shape, _ -> shape }
                .flatMapLatest { shape -> if (shape.transferring) walkTicks() else flowOf(Unit) }
                .map {
                    StorageUsage(
                        usedBytes = storage.usedBytes(),
                        availableBytes = storage.availableBytes(),
                        rootPath = storage.rootPath,
                    )
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        /**
         * Bytes [itemId] occupies on disk right now. An id that does not parse names nothing on disk
         * either, so it answers `null` rather than failing the screen over it.
         */
        override fun observeBytesOnDisk(itemId: String): Flow<Long?> {
            val id = itemId.toUuidOrNull() ?: return flowOf(null)
            return downloadDao
                .observeBytesOnDisk(id)
                // `download_files` is written on every throttled progress sample of *any* item in the
                // queue, and Room re-runs the `SUM` for each of them.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)
        }

        /**
         * The download table reduced to what changes which files exist.
         *
         * Two flows subscribe ([observeStorage] and [observeStorageLocations]) and the Downloads
         * screen shows both at once, so a cold flow would mean two full reads of the table per
         * progress sample — the samples this projection exists to *not* pay for.
         *
         * `shareIn(replay = 1)` rather than `stateIn`: a `StateFlow` needs an initial value, and the
         * only honest one is an empty table, which both consumers would briefly believe. `by lazy` for
         * [downloadStates]' reason.
         */
        private val downloadShape: Flow<DownloadShape> by lazy {
            downloadDao
                .observeProgress()
                .map { rows -> DownloadShape(rows.mapTo(mutableSetOf()) { it.itemId to it.status }) }
                .distinctUntilChanged()
                .flowOn(ioDispatcher)
                .shareIn(
                    scope = appScope,
                    started = SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS),
                    replay = 1,
                )
        }

        /**
         * `locations.resolve()` re-scans the mounted volumes, so it must not be keyed on raw
         * `observeProgress()`; only the *count* of downloads matters, which [downloadShape] answers.
         */
        override fun observeStorageLocations(): Flow<StorageLocations> =
            combine(downloadShape, locations.selectedVolumeId) { shape, selectedId ->
                val selection = locations.resolve(selectedId)
                StorageLocations(
                    volumes = selection.volumes.map(DownloadVolume::toOption),
                    activeVolumeId = selection.active?.id,
                    selectedVolumeMissing = selection.selectionMissing,
                    downloadCount = shape.rows.size,
                )
            }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        override suspend fun setStorageLocation(
            volumeId: String,
            deleteExistingDownloads: Boolean,
        ): AppResult<Unit> =
            withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    // Refuse an id no mounted volume answers to rather than storing it: the manager
                    // would fall back to the primary, and the picker would show a selection the user
                    // never made.
                    if (locations.resolve(volumeId).active?.id != volumeId) {
                        return@withContext AppResult.Failure(AppError.NotFound(volumeId))
                    }

                    if (locations.activeVolume()?.id == volumeId) {
                        // Already writing here, so nothing moves and nothing has to be deleted. This
                        // is also the path that clears a *stale* choice the user can no longer reach.
                        locations.select(volumeId)
                        return@withContext AppResult.Success(Unit)
                    }

                    val existing = downloadDao.allItemIds()
                    if (existing.isNotEmpty() && !deleteExistingDownloads) {
                        // Not an exception: the caller asked whether it could, and the answer is no.
                        Timber.w("Refusing to switch storage to %s: %d downloads exist", volumeId, existing.size)
                        return@withContext AppResult.Failure(AppError.Storage())
                    }

                    if (existing.isNotEmpty()) {
                        // Claim, stop, then delete — before the root moves, or the cascade would look
                        // on the new volume for files that are on the old one, and the downloader
                        // could still be holding a handle. The claim is not decoration:
                        // `deleteUnlessRunnable` skips rows the queue can still reach, so an
                        // unclaimed QUEUED row would survive the switch pointing at the old volume.
                        downloadDao.demoteRunnable(existing, DownloadStatus.CANCELLED, clock.instant())
                        scheduler.stop()
                        deleter.deleteAll(existing)
                    }

                    locations.select(volumeId)
                    AppResult.Success(Unit)
                } catch (cancellation: CancellationException) {
                    // Settings' scope, so this is the user leaving the screen mid-switch rather than
                    // a switch that failed.
                    throw cancellation
                } catch (error: Exception) {
                    // Stays broad: the block mixes Room, the filesystem and WorkManager, so narrowing
                    // to `SQLiteException` would let an ejected card crash instead of answering.
                    Timber.e(error, "Could not switch download storage to %s", volumeId)
                    AppResult.Failure(AppError.Storage(error))
                }
            }

        override val wifiOnly: Flow<Boolean> get() = preferences.downloadOverWifiOnly

        override suspend fun setWifiOnly(enabled: Boolean) {
            preferences.setDownloadOverWifiOnly(enabled)
            // A running job keeps the constraints it was enqueued with, so the new rule only takes
            // effect on a restart.
            scheduler.restart()
        }

        override suspend fun enqueue(itemId: String): AppResult<Unit> {
            val id = itemId.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(itemId))
            val userId =
                (sessionRepository.sessionState.value as? SessionState.LoggedIn)?.userId
                    ?: return AppResult.Failure(AppError.Unauthorized())

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
                // One transaction decides *and* writes: a separate read-then-write leaves a window for
                // the drain's claim, and the item the user just paused would download to completion
                // with nobody left to stop it. Only the transferring row needs the worker cancelled.
                val interruptsTransfer =
                    downloadDao.demoteRunnable(listOf(id), DownloadStatus.PAUSED, clock.instant())
                if (interruptsTransfer) {
                    // Cancelling the work is the only way to interrupt a transfer already in flight.
                    scheduler.stop()
                    scheduler.ensureRunning()
                }
            }

        override suspend fun resume(itemId: String): AppResult<Unit> =
            mutate(itemId) { id ->
                downloadDao.setStatus(id, DownloadStatus.QUEUED, clock.instant())
                // A user asking again is a fresh start: a row that spent its retry budget on a server
                // that was down must not be worth exactly one attempt now that it is back.
                downloadDao.clearAttempts(id)
                scheduler.restartOrJoin(downloadDao)
            }

        override suspend fun pauseAll(itemIds: List<String>): AppResult<Unit> =
            mutateAll(itemIds) { ids ->
                // Same guarded write as the single pause: the batch already excludes the running
                // transcode, and stopping the worker for it anyway would restart that encode from byte
                // zero — the exact thing the button is designed not to do.
                val interruptsTransfer =
                    downloadDao.demoteRunnable(ids, DownloadStatus.PAUSED, clock.instant())
                if (interruptsTransfer) {
                    scheduler.stop()
                    scheduler.ensureRunning()
                }
            }

        override suspend fun resumeAll(itemIds: List<String>): AppResult<Unit> =
            mutateAll(itemIds) { ids ->
                downloadDao.requeueForUser(ids, clock.instant())
                scheduler.restartOrJoin(downloadDao)
            }

        override suspend fun delete(itemId: String): AppResult<Long> = deleteAll(listOf(itemId))

        override suspend fun deleteAll(itemIds: List<String>): AppResult<Long> {
            val ids = itemIds.map { it.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(it)) }
            // Nothing to unlink, so nothing worth stopping the queue for.
            if (ids.isEmpty()) return AppResult.Success(0L)

            return withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    // Take the targets out of the queue's reach *before* unlinking anything: the same
                    // transaction flips every runnable target to CANCELLED and answers whether the
                    // live transfer was among them, so a later claim refuses the row and `stop()` is
                    // guaranteed to be behind us. Conditional on that answer, or an unrelated
                    // in-flight transcode is cancelled from byte zero.
                    if (downloadDao.demoteRunnable(ids, DownloadStatus.CANCELLED, clock.instant())) {
                        scheduler.stop()
                    }
                    // One cascade for the whole batch — a per-row loop would re-run the orphan prune
                    // (a full-table metadata read) once per deleted item.
                    val freed = deleter.deleteAll(ids)
                    // Something else may still be queued behind the deleted items.
                    scheduler.ensureRunning()
                    AppResult.Success(freed)
                } catch (cancellation: CancellationException) {
                    // See `mutate` below: a cancelled caller is not a failed delete.
                    throw cancellation
                } catch (error: Exception) {
                    // Broad on purpose — `deleter.deleteAll` unlinks files, so this catches an ejected volume too.
                    Timber.e(error, "Could not delete downloads %s", itemIds)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        /**
         * `unfinished()`, not `pending()`: this must renumber exactly the list the user reordered, or
         * the rows the engine skips keep a stale position and drift past the ones they were listed
         * between. The target arrives as an **id** for the same reason — the caller cannot index into
         * a snapshot it does not hold — and the id doubles as the guard: a target that finished or was
         * deleted between the tap and this read is absent here, there is no place left to take, and
         * nothing is written. A no-op rather than a failure, since the row aimed at is already gone
         * from the list it was aimed at in.
         */
        override suspend fun move(
            itemId: String,
            targetItemId: String,
        ): AppResult<Unit> =
            mutate(itemId) { id ->
                // One transaction, per the read-decide-write rule: the identity guard and the
                // renumber both resolve against this snapshot, and a row changing status between
                // the read and the last write would split the list the guard promised to keep.
                transactionRunner.inTransaction {
                    val targetId = targetItemId.toUuidOrNull() ?: return@inTransaction
                    val queue = downloadDao.unfinished()
                    // Resolved before the moved row leaves the list: dropping it first would shift
                    // every index below it and land a downward move one place short.
                    val target =
                        queue.indexOfFirst { it.itemId == targetId }.takeIf { it >= 0 }
                            ?: return@inTransaction

                    val reordered = queue.filter { it.itemId != id }.toMutableList()
                    downloadDao.get(id)?.let { reordered.add(target.coerceAtMost(reordered.size), it) }

                    val now = clock.instant()
                    // Renumbered from zero on every move: gaps left by completed or deleted items
                    // would otherwise make "position" mean something other than "place in the list".
                    reordered.forEachIndexed { index, row ->
                        if (row.queuePosition != index) downloadDao.setQueuePosition(row.itemId, index, now)
                    }
                }
            }

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
                } catch (cancellation: CancellationException) {
                    // Every mutation runs in the *caller's* coroutine — a ViewModel scope that dies
                    // with the screen — and a cancelled scope is not a failed pause.
                    throw cancellation
                } catch (error: Exception) {
                    // Not narrowed to `SQLiteException`: `block` also drives `DownloadScheduler`, and
                    // a pause that cannot reach WorkManager is a failed pause, not a crash.
                    Timber.e(error, "Download operation failed for %s", itemId)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        /**
         * [mutate] for a whole set of rows: the block is called **once**, with every id. An empty list
         * is a success that does nothing — a bulk action whose targets all finished while the user was
         * reaching for the button must not stop and restart the queue for nothing.
         */
        private suspend fun mutateAll(
            itemIds: List<String>,
            block: suspend (List<UUID>) -> Unit,
        ): AppResult<Unit> {
            val ids = itemIds.map { it.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(it)) }
            if (ids.isEmpty()) return AppResult.Success(Unit)

            return withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    block(ids)
                    AppResult.Success(Unit)
                } catch (cancellation: CancellationException) {
                    // A torn-down scope during *Pause all* is an ordinary cancel. See [mutate].
                    throw cancellation
                } catch (error: Exception) {
                    // Broad for [mutate]'s reason: the block drives the scheduler as well as Room.
                    Timber.e(error, "Bulk download operation failed for %s", itemIds)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        /**
         * Joins download rows to the cached items they belong to — one lookup for the whole list, since
         * the Downloads screen re-reads on every throttled progress write. The seasons the episodes
         * belong to are a **second** batched lookup over the same cached table, not a query per row.
         */
        private suspend fun toDownloadItems(
            rows: List<DownloadWithFiles>,
            metadata: DownloadMetadataCache,
            seasons: SeasonArtworkCache,
        ): List<DownloadItem> {
            // Both asked even for an empty list, which is what lets the caches forget a deleted download.
            val items = metadata.itemsFor(rows.map { it.download.itemId })
            val seasonArtwork = seasons.artworkFor(items.values)
            if (rows.isEmpty()) return emptyList()

            return rows.map { row ->
                val item = items[row.download.itemId]
                DownloadItem(
                    itemId = row.download.itemId.toString(),
                    title = row.download.itemName,
                    seriesName = row.download.seriesName,
                    status = row.download.status,
                    bytesDownloaded = row.download.bytesDownloaded,
                    bytesTotal = row.download.bytesTotal,
                    bytesOnDisk = row.bytesOnDisk,
                    queuePosition = row.download.queuePosition,
                    quality = row.download.quality,
                    projectedBytes = row.download.projectedBytes,
                    sizeIsExact = row.download.sizeIsExact,
                    errorMessage = row.download.errorMessage,
                    itemType = row.download.itemType,
                    albumName = row.download.albumName,
                    artistName = row.download.artistName,
                    groupId = row.download.groupId,
                    item = item,
                    seasonArtworkUrl = item?.seasonId?.let(seasonArtwork::get),
                )
            }
        }

        internal companion object {
            /**
             * Long enough that the walk costs nothing measurable over a transfer, short enough that the
             * Downloads header is never more than a quarter-minute stale.
             */
            val STORAGE_WALK_INTERVAL: Duration = 15.seconds

            /**
             * Long enough to survive a rotation or a there-and-back between two badge-showing screens
             * without dropping and re-querying; short enough that leaving them all stops the query.
             */
            const val STATES_STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * The parsed metadata of the downloaded items, kept across the emissions of one subscription.
 *
 * `cachedAt` is the whole key: it is bumped by every write to an `items` row and by nothing else, so
 * an entry survives exactly as long as the blob it was decoded from. [ItemDao.getCacheKeys] reads that
 * column *without* the `dto` blob, which is what makes the steady state of a transfer one narrow query
 * per emission instead of a full re-parse.
 *
 * A failed parse is cached as a `null` item rather than dropped, or a corrupt blob would be re-decoded
 * on every progress write. Not thread-safe by design — see [DownloadRepositoryImpl.observeDownloads].
 */
private class DownloadMetadataCache(
    private val itemDao: ItemDao,
    private val itemMapper: ItemEntityMapper,
) {
    private val parsed = mutableMapOf<UUID, CachedMetadata>()

    /**
     * The items behind [ids], parsing only the rows whose cached blob changed since last time.
     *
     * The `getCacheKeys` probe runs on **every** emission on purpose: it is the only thing that
     * notices a *metadata* refresh written behind an open Downloads screen, which is pinned by
     * `DownloadRepositoryImplTest`.
     */
    suspend fun itemsFor(ids: List<UUID>): Map<UUID, JellyfinItem> {
        if (ids.isEmpty()) {
            parsed.clear()
            return emptyMap()
        }

        val keys = itemDao.getCacheKeys(ids)
        val stale = keys.filter { parsed[it.id]?.cachedAt != it.cachedAt }.map { it.id }

        if (stale.isNotEmpty()) {
            itemDao.getItems(stale).forEach { entity ->
                parsed[entity.id] = CachedMetadata(entity.cachedAt, itemMapper.toDomainOrNull(entity))
            }
        }

        // A deleted download must not keep its metadata alive for the rest of the subscription.
        parsed.keys.retainAll(keys.mapTo(mutableSetOf()) { it.id })

        return buildMap {
            keys.forEach { key -> parsed[key.id]?.item?.let { put(key.id, it) } }
        }
    }
}

private class CachedMetadata(
    val cachedAt: Instant,
    val item: JellyfinItem?,
)

/**
 * The poster of every season the downloaded episodes belong to, keyed by the `seasonId` the episode
 * itself carries. The season is a cached parent row of its own — `DownloadedMetadataRefresher` and
 * `DownloadEnqueuer` both write it — so resolving it here is a join, not a network read.
 *
 * A second [DownloadMetadataCache] rather than the item one: that cache evicts every id it was not
 * just asked for, and the seasons are not downloads. Both probes are `getCacheKeys`, which reads no
 * blob, so the steady state of a transfer stays two narrow queries per emission and no parse —
 * the same bargain the item join makes, and the reason a header's poster costs nothing on the
 * two-to-six-writes-a-second progress path.
 */
private class SeasonArtworkCache(
    itemDao: ItemDao,
    itemMapper: ItemEntityMapper,
) {
    private val metadata = DownloadMetadataCache(itemDao, itemMapper)

    /** `seasonId` as the episode spells it → the season's primary image; absent where either is gone. */
    suspend fun artworkFor(items: Collection<JellyfinItem>): Map<String, String> {
        // Parsed once per distinct season, and the raw id is kept: it is what the episode is keyed by,
        // so the lookup never has to agree with `UUID.toString()` about formatting.
        val ids =
            items
                .mapNotNullTo(LinkedHashSet()) { item -> item.seasonId }
                .mapNotNull { raw -> raw.toUuidOrNull()?.let { raw to it } }
        val seasons = metadata.itemsFor(ids.map { (_, id) -> id })
        return buildMap {
            ids.forEach { (raw, id) -> seasons[id]?.primaryImageUrl?.let { put(raw, it) } }
        }
    }
}

/**
 * The coarse shape of the download table. Byte counts are deliberately absent: they are the part that
 * changes twice a second, and what they do to the files on disk is covered by the transfer tick.
 */
private data class DownloadShape(
    val rows: Set<Pair<UUID, DownloadStatus>>,
) {
    /** Whether some file is being written right now, and so is growing between two walks. */
    val transferring: Boolean get() = rows.any { (_, status) -> status == DownloadStatus.DOWNLOADING }
}

/**
 * What [restartOrJoin] consults before restarting the worker: a restart cancels the running job, and
 * a cancelled transcode restarts from byte zero. Pause and delete do *not* read this — their answer
 * has to be atomic with the status write, so they get it from `DownloadDao.demoteRunnable`.
 */
private suspend fun DownloadDao.isDownloadingAnything(): Boolean =
    pending().any { row -> row.status == DownloadStatus.DOWNLOADING }

/**
 * Brings the queue up for freshly re-queued rows without disturbing a live transfer. `restart()`
 * re-reads the constraints and breaks the worker out of a retry backoff, but it does so by
 * **cancelling** the running worker — discarding every byte of an unrelated mid-transcode. With a live
 * transfer the drain loop picks the re-queued rows up from `nextRunnable()` on its own, and
 * `ensureRunning` (KEEP) merely covers the race where it finishes in between.
 */
private suspend fun DownloadScheduler.restartOrJoin(dao: DownloadDao) {
    if (dao.isDownloadingAnything()) ensureRunning() else restart()
}

/**
 * A heartbeat for as long as a transfer is running. The first emission is immediate so a status change
 * is reflected at once; everything after it exists only so the number creeps while the bytes do.
 */
private fun walkTicks(): Flow<Unit> =
    flow {
        while (true) {
            emit(Unit)
            delay(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL)
        }
    }

/** `availableBytes` is read here, on the IO dispatcher, rather than lazily on the main thread. */
private fun DownloadVolume.toOption(): StorageVolumeOption =
    StorageVolumeOption(
        id = id,
        description = description,
        isRemovable = isRemovable,
        path = directory.absolutePath,
        availableBytes = availableBytes,
    )

internal fun DownloadProgress.toDownloadState(): DownloadState = status.toDownloadState(bytesDownloaded, bytesTotal)

/**
 * `DownloadStatus` (persistence) becomes `DownloadState` (UI) here. They are deliberately separate
 * types: the UI's `Downloading` carries a progress fraction and has no use for `CANCELLED`, which only
 * ever exists between a cancel and the row's deletion.
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
