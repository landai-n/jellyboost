package dev.jellyfinnative.data.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadProgress
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.di.ApplicationScope
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageLocations
import dev.jellyfinnative.data.downloads.model.StorageUsage
import dev.jellyfinnative.data.downloads.model.StorageVolumeOption
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import dev.jellyfinnative.data.downloads.storage.DownloadVolume
import dev.jellyfinnative.data.downloads.storage.StorageLocationManager
import dev.jellyfinnative.data.downloads.work.DownloadScheduler
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
        private val locations: StorageLocationManager,
        private val preferences: AppPreferences,
        private val sessionRepository: SessionRepository,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : DownloadRepository {
        /**
         * The one Room subscription every `DownloadBadge` in the app reads from.
         *
         * Four ViewModels (`LibraryViewModel`, `HomeViewModel`, `SearchViewModel`,
         * `ItemDetailViewModel`) each used to call [observeStates] and get their own cold flow back
         * — four independent `observeProgress()` collectors, each re-running the same map and
         * `distinctUntilChanged` over the same rows (docs/notes/audit-2026-07.md, PERF-07). Sharing
         * one [stateIn] here means Room is asked once no matter how many screens are showing badges
         * at once. `WhileSubscribed` still lets it stop when nothing is: badges are not worth a
         * standing query with every screen backgrounded.
         *
         * `by lazy`, not a plain `val`: this is a constructor property, and every one of this
         * class's other ~40 unit tests constructs one without stubbing `observeProgress()` at all.
         * Building the chain eagerly would call it unconditionally and fail every test that never
         * touches [observeStates] — laziness defers the call to the first real subscriber, same as
         * before.
         */
        private val downloadStates: StateFlow<Map<String, DownloadState>> by lazy {
            downloadDao
                .observeProgress()
                .map { rows -> rows.associate { it.itemId.toString() to it.toDownloadState() } }
                // Progress writes land up to twice a second; without this every card in the app
                // would recompose on each of them even when no badge actually changed.
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
         * The join is **memoised** per subscription. Rebuilding it from scratch meant a `SELECT *`
         * over every downloaded item and a `Json.decodeFromString` of each one's full
         * `BaseItemDto` — tens of kilobytes apiece — on every emission, which is two to six times a
         * second for the whole length of a transfer; the `distinctUntilChanged` below suppressed the
         * recomposition but none of the parsing (docs/notes/audit-2026-07.md, PERF-01). What a
         * progress write actually changes is a byte count, and metadata is keyed on `cachedAt`, so
         * [DownloadMetadataCache] re-reads a blob only when the row behind it was rewritten.
         *
         * The cache is created *inside* the flow rather than held as a field: it is then touched
         * only by this flow's own collector, which is what makes it safe without a lock, and it dies
         * with the subscription instead of holding every downloaded item's metadata for the life of
         * the process.
         */
        override fun observeDownloads(): Flow<List<DownloadItem>> =
            flow {
                val metadata = DownloadMetadataCache(itemDao, itemMapper)
                emitAll(downloadDao.observeAll().map { rows -> toDownloadItems(rows, metadata) })
            }
                // `observeAll` is a `@Transaction` over two tables, so one throttled progress
                // update re-emits it two or three times — once for the file row, once for the item
                // row. Only the emissions that actually changed something are worth a recomposition.
                .distinctUntilChanged()
                .flowOn(ioDispatcher)

        /**
         * How much of the volume the downloads root occupies, and how much of it is left.
         *
         * `usedBytes()` is a `stat()` of *every* file under the root — media, subtitles, artwork and
         * one per trickplay tile — so what it may not be keyed on is `observeProgress` itself, which
         * lands twice a second for the whole of a transfer and would pay for the walk on each of them
         * (docs/notes/audit-2026-07.md, PERF-02; the `distinctUntilChanged` below suppresses the
         * recomposition but not the walk). Three coarser things move the number instead:
         *
         * - the **shape** of the download table — which items exist and what status each is in. That
         *   is what actually adds and removes files, and it changes a handful of times per download
         *   rather than hundreds;
         * - a slow [STORAGE_WALK_INTERVAL] tick while something is `DOWNLOADING`, because the file on
         *   disk grows without any row's *shape* changing, and the header would otherwise sit still
         *   for the length of an episode;
         * - the selected volume, which is a trigger and not decoration: switching location with an
         *   empty queue changes no download row, so keying only on the table would leave the header
         *   reporting the *old* volume's free space until the next enqueue.
         *
         * `StatFs` is cheap, but it rides the same cadence: it describes the same volume as the walk,
         * and reporting the two from different instants would only make the header disagree with
         * itself.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observeStorage(): Flow<StorageUsage> =
            combine(downloadShape(), locations.selectedVolumeId) { shape, _ -> shape }
                .flatMapLatest { shape -> if (shape.transferring) walkTicks() else flowOf(Unit) }
                .map {
                    StorageUsage(
                        usedBytes = storage.usedBytes(),
                        availableBytes = storage.availableBytes(),
                        rootPath = storage.rootPath,
                    )
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        /** The download table reduced to what changes which files exist. */
        private fun downloadShape(): Flow<DownloadShape> =
            downloadDao
                .observeProgress()
                .map { rows -> DownloadShape(rows.mapTo(mutableSetOf()) { it.itemId to it.status }) }
                .distinctUntilChanged()

        /**
         * A heartbeat for as long as a transfer is running, starting with the moment it starts.
         *
         * The first emission is immediate so a status change is reflected at once; everything after
         * it exists only so the number creeps while the bytes do.
         */
        private fun walkTicks(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(STORAGE_WALK_INTERVAL)
                }
            }

        /**
         * `locations.resolve()` re-scans the mounted volumes, which is not free — and used to be
         * keyed on raw `observeProgress()`, the same 2/s hot path [observeStorage] moved off of
         * (PERF-02). Only the *count* of downloads matters here, and [downloadShape] already
         * answers that at the rate it can actually change (docs/notes/audit-2026-07.md, PERF-13).
         */
        override fun observeStorageLocations(): Flow<StorageLocations> =
            combine(downloadShape(), locations.selectedVolumeId) { shape, selectedId ->
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
                    // would fall back to the primary volume, and the picker would show a selection
                    // the user never made.
                    if (locations.resolve(volumeId).active?.id != volumeId) {
                        return@withContext AppResult.Failure(AppError.NotFound(volumeId))
                    }

                    if (locations.activeVolume()?.id == volumeId) {
                        // Already writing here, so nothing moves and nothing has to be deleted.
                        // This is the path that clears a *stale* choice: with the card out, the
                        // user can tap the volume the fallback already picked and make it the
                        // choice, instead of being stuck with a selection they cannot reach.
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
                        // Same order as a single delete: stop the queue before unlinking anything,
                        // so the downloader cannot be holding a handle to a file we remove — and
                        // delete *before* the root moves, or the cascade would look on the new
                        // volume for files that are on the old one.
                        scheduler.stop()
                        existing.forEach { deleter.delete(it) }
                    }

                    locations.select(volumeId)
                    AppResult.Success(Unit)
                } catch (error: Exception) {
                    Timber.e(error, "Could not switch download storage to %s", volumeId)
                    AppResult.Failure(AppError.Storage(error))
                }
            }

        override val wifiOnly: Flow<Boolean> get() = preferences.downloadOverWifiOnly

        override suspend fun setWifiOnly(enabled: Boolean) {
            preferences.setDownloadOverWifiOnly(enabled)
            // A running job keeps the constraints it was enqueued with, so the new rule only takes
            // effect on a restart — which is what makes the toggle feel immediate.
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
                downloadDao.setStatus(id, DownloadStatus.PAUSED, clock.instant())
                // Stop first, then restart: the running job may be on *this* item, and the only way
                // to interrupt it is to cancel the work. The restart picks up whatever is left.
                scheduler.stop()
                scheduler.ensureRunning()
            }

        override suspend fun resume(itemId: String): AppResult<Unit> =
            mutate(itemId) { id ->
                downloadDao.setStatus(id, DownloadStatus.QUEUED, clock.instant())
                // A user asking again is a fresh start: a row that spent its retry budget on a
                // server that was down must not be worth exactly one attempt now that it is back.
                downloadDao.clearAttempts(id)
                scheduler.restart()
            }

        override suspend fun pauseAll(itemIds: List<String>): AppResult<Unit> =
            mutateAll(itemIds) { ids ->
                downloadDao.setStatusIn(ids, DownloadStatus.PAUSED, clock.instant())
                // Same order as the single pause, once instead of once per row: the running job may
                // be on any of these, and the restart picks up whatever is left.
                scheduler.stop()
                scheduler.ensureRunning()
            }

        override suspend fun resumeAll(itemIds: List<String>): AppResult<Unit> =
            mutateAll(itemIds) { ids ->
                downloadDao.requeueForUser(ids, clock.instant())
                scheduler.restart()
            }

        override suspend fun delete(itemId: String): AppResult<Long> = deleteAll(listOf(itemId))

        override suspend fun deleteAll(itemIds: List<String>): AppResult<Long> {
            val ids = itemIds.map { it.toUuidOrNull() ?: return AppResult.Failure(AppError.NotFound(it)) }
            // Nothing to unlink, so nothing worth stopping the queue for.
            if (ids.isEmpty()) return AppResult.Success(0L)

            return withContext(ioDispatcher) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    // Stopping the queue before unlinking files means the downloader cannot be
                    // holding a handle to something we are about to remove — and `stop()` waits for
                    // the worker, so that is true by the time the first directory goes.
                    scheduler.stop()
                    val freed = ids.sumOf { deleter.delete(it) }
                    // Something else may still be queued behind the deleted items.
                    scheduler.ensureRunning()
                    AppResult.Success(freed)
                } catch (error: Exception) {
                    Timber.e(error, "Could not delete downloads %s", itemIds)
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
         * [mutate] for a whole set of rows: the block is called **once**, with every id.
         *
         * An empty list is a success that does nothing — a bulk action whose targets all finished
         * while the user was reaching for the button must not stop and restart the queue for
         * nothing.
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
                } catch (error: Exception) {
                    Timber.e(error, "Bulk download operation failed for %s", itemIds)
                    AppResult.Failure(AppError.Storage(error))
                }
            }
        }

        /**
         * Joins download rows to the cached items they belong to.
         *
         * One lookup for the whole list rather than one per row: the Downloads screen re-reads on
         * every throttled progress write, and a per-row query would make that N round trips through
         * Room twice a second.
         */
        private suspend fun toDownloadItems(
            rows: List<DownloadWithFiles>,
            metadata: DownloadMetadataCache,
        ): List<DownloadItem> {
            // Asked even for an empty list, which is what lets the cache forget a deleted download.
            val items = metadata.itemsFor(rows.map { it.download.itemId })
            if (rows.isEmpty()) return emptyList()

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
                    quality = row.download.quality,
                    projectedBytes = row.download.projectedBytes,
                    sizeIsExact = row.download.sizeIsExact,
                    errorMessage = row.download.errorMessage,
                    item = items[row.download.itemId],
                )
            }
        }

        internal companion object {
            /**
             * How often the downloads tree is re-walked while something is downloading.
             *
             * Long enough that the walk costs nothing measurable over a transfer, short enough that
             * the Downloads header is never more than a quarter-minute stale — which is the whole of
             * what the number is for.
             */
            val STORAGE_WALK_INTERVAL: Duration = 15.seconds

            /**
             * How long [downloadStates] keeps its Room subscription open after the last badge stops
             * collecting it.
             *
             * Long enough to survive a rotation or a there-and-back navigation between two screens
             * that both show badges (a library grid to an item's detail page, say) without dropping
             * and re-querying; short enough that leaving every badge-showing screen behind actually
             * stops the query.
             */
            const val STATES_STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * The parsed metadata of the downloaded items, kept across the emissions of one subscription.
 *
 * `cachedAt` is the whole key: it is bumped by every write to an `items` row and by nothing else,
 * so an entry survives exactly as long as the blob it was decoded from. [ItemDao.getCacheKeys]
 * reads that column *without* the `dto` blob — the projection exists for this shape — which is what
 * makes the steady state of a transfer one narrow query per emission instead of a full re-parse.
 *
 * A failed parse is cached as a `null` item rather than dropped: a corrupt blob would otherwise be
 * re-decoded (and re-failed) on every progress write for as long as the row exists.
 *
 * Not thread-safe by design — see [DownloadRepositoryImpl.observeDownloads] for why it does not
 * need to be.
 */
private class DownloadMetadataCache(
    private val itemDao: ItemDao,
    private val itemMapper: ItemEntityMapper,
) {
    private val parsed = mutableMapOf<UUID, CachedMetadata>()

    /** The items behind [ids], parsing only the rows whose cached blob changed since last time. */
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

/** One item's decoded metadata, together with the blob revision it was decoded from. */
private class CachedMetadata(
    val cachedAt: Instant,
    val item: JellyfinItem?,
)

/**
 * The coarse shape of the download table: which items exist, and what each of them is doing.
 *
 * Byte counts are deliberately absent. They are the part that changes twice a second, and what they
 * do to the files on disk is already covered by the tick that runs while anything is transferring.
 */
private data class DownloadShape(
    val rows: Set<Pair<UUID, DownloadStatus>>,
) {
    /** Whether some file is being written right now, and so is growing between two walks. */
    val transferring: Boolean get() = rows.any { (_, status) -> status == DownloadStatus.DOWNLOADING }
}

/**
 * A volume as the settings picker sees it.
 *
 * `availableBytes` is read here, on the IO dispatcher the projection runs on, rather than exposed as
 * a lazy property the UI would touch on the main thread.
 */
private fun DownloadVolume.toOption(): StorageVolumeOption =
    StorageVolumeOption(
        id = id,
        description = description,
        isRemovable = isRemovable,
        path = directory.absolutePath,
        availableBytes = availableBytes,
    )

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
