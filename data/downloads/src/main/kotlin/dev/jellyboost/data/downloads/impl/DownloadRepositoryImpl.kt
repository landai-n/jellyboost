package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.JellyfinItem
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
internal class DownloadRepositoryImpl
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

        /**
         * Bytes [itemId] occupies on disk right now — what the detail screen shows instead of the
         * server-reported size once the local copy is what the user actually has.
         *
         * An id that does not parse names nothing on disk either, so it answers `null` rather than
         * failing the screen over it.
         */
        override fun observeBytesOnDisk(itemId: String): Flow<Long?> {
            val id = itemId.toUuidOrNull() ?: return flowOf(null)
            return downloadDao.observeBytesOnDisk(id)
        }

        /** The download table reduced to what changes which files exist. */
        private fun downloadShape(): Flow<DownloadShape> =
            downloadDao
                .observeProgress()
                .map { rows -> DownloadShape(rows.mapTo(mutableSetOf()) { it.itemId to it.status }) }
                .distinctUntilChanged()

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
                        // volume for files that are on the old one. Unconditional here on purpose:
                        // every download is about to go, so whatever is running is a target.
                        scheduler.stop()
                        deleter.deleteAll(existing)
                    }

                    locations.select(volumeId)
                    AppResult.Success(Unit)
                } catch (cancellation: CancellationException) {
                    // Settings' scope, so this is the user leaving the screen mid-switch. Reporting
                    // it as `AppError.Storage` would raise "could not change storage" over a screen
                    // that is already gone and swallow the cancellation structured concurrency is
                    // owed (audit ARCH-08 / HYG-5).
                    throw cancellation
                } catch (error: Exception) {
                    // Stays broad: the block mixes Room (`allItemIds`), the filesystem (`deleteAll`
                    // unlinks directories) and WorkManager (`scheduler.stop`), so narrowing to
                    // `SQLiteException` would let an ejected card crash the app instead of
                    // answering "could not switch".
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
                // One transaction decides *and* writes. Only the row that is actually transferring
                // needs the worker cancelled — stopping it for any other row would cancel an
                // unrelated in-flight transcode from byte zero (audit DL-06) — but a separate
                // read-then-write left a window for the drain's claim to slip between the two, and
                // the item the user had just paused downloaded to completion with nobody left to
                // stop it (audit DL-03; see DownloadDao.demoteRunnable).
                val interruptsTransfer =
                    downloadDao.demoteRunnable(listOf(id), DownloadStatus.PAUSED, clock.instant())
                if (interruptsTransfer) {
                    // Stop first, then restart: cancelling the work is the only way to interrupt a
                    // transfer already in flight. The restart picks up whatever is left.
                    scheduler.stop()
                    scheduler.ensureRunning()
                }
            }

        override suspend fun resume(itemId: String): AppResult<Unit> =
            mutate(itemId) { id ->
                downloadDao.setStatus(id, DownloadStatus.QUEUED, clock.instant())
                // A user asking again is a fresh start: a row that spent its retry budget on a
                // server that was down must not be worth exactly one attempt now that it is back.
                downloadDao.clearAttempts(id)
                scheduler.restartOrJoin(downloadDao)
            }

        override suspend fun pauseAll(itemIds: List<String>): AppResult<Unit> =
            mutateAll(itemIds) { ids ->
                // Same guarded write as the single pause. This is what keeps the "Pause all keeps
                // transcodes" promise honest: the batch already excludes the running transcode, so
                // stopping the worker for it anyway restarted that very encode from byte zero —
                // the exact thing the button was designed not to do (audit DL-06). And the
                // decision rides in the same transaction as the write, so a drain claim cannot
                // land between the two and keep transferring a row just marked PAUSED (DL-03).
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
                    // Take the targets out of the queue's reach *before* unlinking anything: the
                    // same transaction flips every QUEUED/DOWNLOADING target to CANCELLED — the
                    // status a row holds between a cancel and its deletion — and answers whether
                    // the live transfer was among them. A claim arriving later refuses a CANCELLED
                    // row, so `stop()` (which waits for the worker) is guaranteed to be behind us
                    // whenever a target was being written when the first directory goes. Stopping
                    // is still conditional on that answer: doing it for any other row would cancel
                    // an unrelated in-flight transcode from byte zero (audit DL-06), and a plain
                    // read-then-stop left the DL-03 window where a claim landing between the two
                    // let files be unlinked under a live writer.
                    if (downloadDao.demoteRunnable(ids, DownloadStatus.CANCELLED, clock.instant())) {
                        scheduler.stop()
                    }
                    // One cascade for the whole batch — the per-row loop re-ran the orphan prune
                    // (a full-table metadata read) once per deleted item (audit DL-05).
                    val freed = deleter.deleteAll(ids)
                    // Something else may still be queued behind the deleted items.
                    scheduler.ensureRunning()
                    AppResult.Success(freed)
                } catch (cancellation: CancellationException) {
                    // See `mutate` below: a cancelled caller is not a failed delete.
                    throw cancellation
                } catch (error: Exception) {
                    // Broad on purpose — `deleter.deleteAll` unlinks files, so this catches an
                    // ejected volume as much as it catches Room.
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
                } catch (cancellation: CancellationException) {
                    // Every mutation runs in the *caller's* coroutine — a ViewModel scope that dies
                    // with the screen. A cancelled scope is not a failed pause: folding it into
                    // `AppError.Storage` puts an error on a screen the user has left, spends the
                    // caller's retry budget on their own back-press, and swallows the cancellation
                    // the machinery is owed (audit ARCH-08 / HYG-5, same shape as `DownloadEnqueuer`).
                    throw cancellation
                } catch (error: Exception) {
                    // Not narrowed to `SQLiteException` like the enqueuer's: `block` is caller-
                    // supplied and every caller also drives `DownloadScheduler`, whose WorkManager
                    // failures are not Room's. A pause that cannot reach WorkManager is still a
                    // failed pause, not a crash.
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
                } catch (cancellation: CancellationException) {
                    // The bulk path is the one the audit caught in the act (HYG-5): a torn-down
                    // scope during *Pause all* logged at E and answered `Failure(Storage)` for an
                    // ordinary cancel. See [mutate].
                    throw cancellation
                } catch (error: Exception) {
                    // Broad for [mutate]'s reason: the block drives the scheduler as well as Room.
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
 * Whether the worker is transferring anything at all right now.
 *
 * What [restartOrJoin] consults before restarting the worker (audit DL-06): a restart cancels the
 * running job, and a cancelled transcode restarts from byte zero, since a live encode can never be
 * resumed. Pause and delete no longer read this — their answer has to be atomic with the status
 * write, so they get it from `DownloadDao.demoteRunnable` instead (audit DL-03). Top-level rather
 * than a method for detekt's function-count ceiling on the repository, like [walkTicks].
 */
private suspend fun DownloadDao.isDownloadingAnything(): Boolean =
    pending().any { row -> row.status == DownloadStatus.DOWNLOADING }

/**
 * Brings the queue up for freshly re-queued rows without disturbing a live transfer.
 *
 * A `restart()` is only the right move while nothing is downloading: it re-reads the constraints
 * and breaks the worker out of a retry backoff, which is what a user tapping *Resume* expects. But
 * it restarts by **cancelling** the running worker — and if that worker is mid-transcode on an
 * unrelated item, every transferred byte is discarded (audit DL-06). With a live transfer the
 * worker is already awake and its drain loop picks the re-queued rows up from `nextRunnable()` on
 * its own; `ensureRunning` (KEEP) merely covers the race where it finishes in between.
 */
private suspend fun DownloadScheduler.restartOrJoin(dao: DownloadDao) {
    if (dao.isDownloadingAnything()) ensureRunning() else restart()
}

/**
 * A heartbeat for as long as a transfer is running, starting with the moment it starts —
 * [DownloadRepositoryImpl.observeStorage]'s slow re-walk of the downloads tree.
 *
 * The first emission is immediate so a status change is reflected at once; everything after it
 * exists only so the number creeps while the bytes do.
 *
 * A top-level function rather than a method on the repository: [DownloadRepositoryImpl] is at
 * detekt's function-count ceiling (`TooManyFunctions`, threshold 20), and this depends on nothing
 * but the companion's own interval.
 */
private fun walkTicks(): Flow<Unit> =
    flow {
        while (true) {
            emit(Unit)
            delay(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL)
        }
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
