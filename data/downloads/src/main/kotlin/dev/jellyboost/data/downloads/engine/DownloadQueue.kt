package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.network.session.SessionGate
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.plan.DownloadFilePlanner
import dev.jellyboost.data.downloads.plan.PlannedFile
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.StorageUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal interface DownloadQueueListener {
    /** Called on every throttled Room write. */
    suspend fun onProgress(
        download: DownloadEntity,
        bytesDownloaded: Long,
        bytesTotal: Long,
    )

    suspend fun onIdle()
}

/** How a drain ended: the worker reports [COMPLETED]/[INCOMPLETE] and re-runs [RETRY]/[NO_SESSION]. */
internal enum class DrainOutcome {
    COMPLETED,

    /** The queue ran, and at least one item failed permanently; its row carries the reason. */
    INCOMPLETE,

    /** The rest of the queue is untouched and still QUEUED: whatever failed will fail for every row behind it. */
    RETRY,

    /** Nothing was attempted: no row is left claiming a failure it never had. */
    NO_SESSION,
}

private enum class ItemOutcome { SUCCEEDED, FAILED, RETRY }

internal class MissingMetadataException(
    itemId: UUID,
) : IllegalStateException("No cached metadata for $itemId")

/**
 * Runs the download queue: one item at a time, in `queuePosition` order.
 *
 * Non-media files failing are logged and ignored; a media failure is classified — permanent marks the
 * item ERROR and the drain moves on, transient leaves the row `QUEUED` with a raised
 * [DownloadEntity.attemptCount] and *stops* the drain, so one server blip cannot fail forty rows.
 * [drain] holds a process-wide lease: a `REPLACE` enqueue starts the new worker while the old one is
 * still unwinding, and both would run `requeueInterrupted` over the same open file.
 * A cancellation re-queues the row unless something already gave it another status, which a pause does.
 */
@Singleton
internal class DownloadQueue
    @Suppress(
        "LongParameterList",
    )
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val itemMapper: ItemEntityMapper,
        private val planner: DownloadFilePlanner,
        private val storage: DownloadStorage,
        private val downloader: FileDownloader,
        private val extractor: AudioSidecarExtractor,
        private val seeder: SiblingSeeder,
        private val sweeper: OrphanSweeper,
        private val sessionGate: SessionGate,
        private val transactionRunner: TransactionRunner,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val drainLease = Mutex()

        /** One publication at a time: an item's two lanes must never write the row and notify at once. */
        private val progressLease = Mutex()

        suspend fun drain(listener: DownloadQueueListener): DrainOutcome =
            drainLease.withLock { drainExclusively(listener) }

        private suspend fun drainExclusively(listener: DownloadQueueListener): DrainOutcome {
            // Rows left DOWNLOADING belong to a process that no longer exists. Re-queued before the
            // session gate, so a parked queue still reads as "Waiting".
            downloadDao.requeueInterrupted(clock.instant())
            sweeper.sweep()

            if (!sessionGate.ensureSession()) {
                listener.onIdle()
                return DrainOutcome.NO_SESSION
            }

            var outcome = DrainOutcome.COMPLETED
            // A retried row is QUEUED again, so carrying on would hand back the same item forever.
            while (outcome != DrainOutcome.RETRY) {
                val next = downloadDao.nextRunnable() ?: break
                outcome =
                    when (process(next, listener)) {
                        // A success must not clear a failure an earlier item already recorded.
                        ItemOutcome.SUCCEEDED -> outcome
                        ItemOutcome.FAILED -> DrainOutcome.INCOMPLETE
                        ItemOutcome.RETRY -> DrainOutcome.RETRY
                    }
            }
            listener.onIdle()
            return outcome
        }

        private suspend fun process(
            queued: DownloadWithFiles,
            listener: DownloadQueueListener,
        ): ItemOutcome {
            val download = queued.download
            // Guarded, not a plain write: `pause` writes PAUSED then stops the worker, and a drain
            // between `nextRunnable()` and here would clobber it. Zero rows means the row changed hands.
            if (downloadDao.markDownloadingIfRunnable(download.itemId, clock.instant()) == 0) {
                Timber.i("%s changed status before its transfer began; leaving it alone", download.itemName)
                return ItemOutcome.SUCCEEDED
            }

            return try {
                val dto = loadDto(download) ?: throw MissingMetadataException(download.itemId)
                val seeded = seedIfUnseeded(download, dto)
                val files = reconcile(seeded, dto, queued.files)
                // `false` means the row was deleted underneath us: the item is gone, not finished.
                if (transfer(seeded, dto, files, listener)) {
                    downloadDao.setStatus(seeded.itemId, DownloadStatus.DOWNLOADED, clock.instant())
                    reseedSiblings(seeded)
                }
                ItemOutcome.SUCCEEDED
            } catch (cancellation: CancellationException) {
                // Not a failure, never a retry. `NonCancellable` so the write survives the already-
                // cancelled coroutine; conditional because *Pause* writes PAUSED and then cancels.
                withContext(NonCancellable) {
                    downloadDao.requeueIfDownloading(download.itemId, clock.instant())
                }
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                fail(download, error)
            }
        }

        /** Records a failed attempt. The counter lives on the row because the retry is a new worker run. */
        private suspend fun fail(
            download: DownloadEntity,
            error: Exception,
        ): ItemOutcome {
            val attempt = download.attemptCount + 1
            val retryable =
                DownloadFailureClassifier.classify(error) == FailureKind.TRANSIENT && attempt < MAX_ATTEMPTS

            if (retryable) {
                Timber.w(
                    error,
                    "Download of %s failed transiently (attempt %d of %d); it stays queued",
                    download.itemName,
                    attempt,
                    MAX_ATTEMPTS,
                )
                downloadDao.requeueForRetry(download.itemId, attempt, clock.instant())
                return ItemOutcome.RETRY
            }

            Timber.e(error, "Download of %s failed", download.itemName)
            downloadDao.setStatus(
                itemId = download.itemId,
                status = DownloadStatus.ERROR,
                updatedAt = clock.instant(),
                // Not `error.message`: that string is rendered to the user (see [DownloadErrorCopy]).
                errorMessage = DownloadErrorCopy.forFailure(error),
            )
            return ItemOutcome.FAILED
        }

        private suspend fun loadDto(download: DownloadEntity): BaseItemDto? =
            itemDao.getItem(download.itemId)?.let(itemMapper::toDtoOrNull)

        /**
         * Brings the stored file rows in line with a freshly-built plan, in plan order.
         *
         * The URL is rebuilt every run (the server's base address rotates between LAN and remote); the
         * **file name never is** — it *is* the partial file on disk, and a re-plan would orphan a
         * half-finished multi-gigabyte transfer. Rows match on (type, stream, tile), the unique index.
         */
        private suspend fun reconcile(
            download: DownloadEntity,
            dto: BaseItemDto,
            existing: List<DownloadFileEntity>,
        ): List<DownloadFileEntity> {
            storage.prepareItemDirectory(download.directoryName)
            // Quality and the baked audio track come from the row, never re-derived: the bytes on
            // disk were fetched at them, and the DTO's default stream is the server's *current* answer.
            val planned =
                planner.plan(
                    dto,
                    download.directoryName,
                    quality = download.quality,
                    audioStreamIndex = download.bakedAudioStreamIndex,
                )

            return planned.map { file ->
                val previous =
                    existing.firstOrNull {
                        it.type == file.type && it.streamIndex == file.streamIndex && it.tileIndex == file.tileIndex
                    }

                if (previous == null) {
                    val path = storage.resolve(download.directoryName, file.fileName).absolutePath
                    val row = file.toEntity(download.itemId, path)
                    row.copy(id = downloadDao.insertFile(row))
                } else {
                    // The path is re-resolved from the *stored* name, so a change of storage root
                    // takes effect without renaming the file.
                    val path = storage.resolve(download.directoryName, previous.fileName).absolutePath
                    val row = previous.copy(path = path, url = file.url)
                    downloadDao.updateFile(row)
                    row
                }
            }
        }

        /**
         * Fetches an item's files: the ordinary ones in plan order, its audio sidecars alongside them.
         *
         * Two lanes because a sidecar is a live `/Videos` transcode capped at its own stream's bitrate,
         * so an item costs `max(media, sidecars)` rather than their sum; the sidecar lane is sequential
         * so the server is never asked for a third simultaneous encode.
         *
         * @return `false` when the item's row disappeared mid-transfer — `FileDownloader` re-creates the
         *   item directory for every file, so carrying on would leave a directory no row points at.
         */
        private suspend fun transfer(
            download: DownloadEntity,
            dto: BaseItemDto,
            files: List<DownloadFileEntity>,
            listener: DownloadQueueListener,
        ): Boolean =
            coroutineScope {
                val progress =
                    ItemProgress(
                        files,
                        estimatedTotal = download.bytesTotal,
                        seededProjection = download.projectedBytes,
                    )
                val (sidecars, ordinary) = files.partition { it.type == DownloadFileType.AUDIO }
                val lane = launch { drainSidecars(download, sidecars, progress, listener) }

                val alive = drainOrdinary(download, dto, ordinary, progress, listener)
                // The row is gone — not a failure, so it does not cancel the scope by itself.
                if (!alive) lane.cancel()
                alive
            }

        private suspend fun drainOrdinary(
            download: DownloadEntity,
            dto: BaseItemDto,
            files: List<DownloadFileEntity>,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ): Boolean {
            val projector = projectorFor(download, dto)

            for (file in files) {
                if (downloadDao.get(download.itemId) == null) {
                    Timber.i("%s was removed while it was downloading; stopping", download.itemName)
                    return false
                }

                // Only the media file is Matroska and worth projecting; one projector per item.
                val fileProjector = projector.takeIf { file.type == DownloadFileType.MEDIA }
                val publisher = ProgressPublisher(download, file, progress, fileProjector, listener)
                if (file.type.essential) {
                    downloadEssential(dto, publisher)
                } else {
                    // try/catch(Exception), not runCatching: the latter would swallow an Error too.
                    try {
                        downloadOne(publisher)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (
                        @Suppress("TooGenericExceptionCaught") error: Exception,
                    ) {
                        Timber.w(error, "Optional file %s failed; item stays playable", file.fileName)
                    }
                }
            }
            return true
        }

        /** Sequential on purpose — see [transfer]'s two-encodes-per-item ceiling; never given a projector. */
        private suspend fun drainSidecars(
            download: DownloadEntity,
            files: List<DownloadFileEntity>,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ) {
            for (file in files) {
                // The delete cascade can land between any two files of this lane too.
                if (downloadDao.get(download.itemId) == null) return

                // try/catch(Exception) for the same reason as the ordinary lane.
                try {
                    downloadOne(ProgressPublisher(download, file, progress, projector = null, listener))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Timber.w(error, "Audio sidecar %s failed; item keeps its other tracks", file.fileName)
                }
            }
        }

        /**
         * Best effort: an item on disk and playable must not be reported failed because a cosmetic
         * estimate for the *next* item could not be written.
         */
        private suspend fun reseedSiblings(download: DownloadEntity) {
            runCatching { seeder.seedPendingSiblingsOf(download) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.w(error, "Could not re-seed the siblings of %s", download.itemName)
                }
        }

        /**
         * Seeds a row's projection if it has none, so a fresh episode is not stuck on "up to X" until
         * the scanner reads its first cluster. Written with `setProjectedBytesIfAbsent`, so a real
         * projection written meanwhile wins.
         */
        @Suppress(
            "ReturnCount",
        )
        private suspend fun seedIfUnseeded(
            download: DownloadEntity,
            dto: BaseItemDto,
        ): DownloadEntity {
            if (download.projectedBytes != null || !download.quality.isTranscoded || download.sizeIsExact) {
                return download
            }
            val runtimeMillis = Ticks.positiveMillisOrNull(dto.runTimeTicks) ?: return download
            val ceiling = download.bytesTotal.takeIf { it > 0L } ?: return download

            val seed =
                seeder.seedFor(
                    itemId = download.itemId,
                    seriesName = download.seriesName,
                    quality = download.quality,
                    runtimeMillis = runtimeMillis,
                    ceilingBytes = ceiling,
                ) ?: return download

            downloadDao.setProjectedBytesIfAbsent(download.itemId, seed, clock.instant())
            return download.copy(projectedBytes = seed)
        }

        /**
         * The live size projection for this item's media file, or `null` when the row's own total is
         * already better: `ORIGINAL` (exact server size), `sizeIsExact` (a recognised stream copy), or
         * no runtime to extrapolate the observed bitrate to.
         */
        private fun projectorFor(
            download: DownloadEntity,
            dto: BaseItemDto,
        ): TranscodeSizeProjector? {
            if (!download.quality.isTranscoded || download.sizeIsExact) return null
            val runtimeMillis = Ticks.positiveMillisOrNull(dto.runTimeTicks) ?: return null
            return TranscodeSizeProjector(runtimeMillis = runtimeMillis, ceilingBytes = download.bytesTotal)
        }

        /**
         * The media file, with the plan's download-policy fallback: a `403` on `/Items/{id}/Download`
         * means the server refused that endpoint for this user, and the same bytes are reachable as a
         * static video stream. `ORIGINAL` only — a transcoded row never asks that endpoint.
         */
        private suspend fun downloadEssential(
            dto: BaseItemDto,
            publisher: ProgressPublisher,
        ) {
            val download = publisher.download
            try {
                downloadOne(publisher)
            } catch (error: DownloadHttpException) {
                if (error.code != HttpURLConnection.HTTP_FORBIDDEN || download.quality.isTranscoded) throw error

                Timber.i("Download endpoint denied for %s; falling back to the video stream", download.itemName)
                val fallback =
                    planner
                        .plan(
                            dto,
                            download.directoryName,
                            downloadAllowed = false,
                            audioStreamIndex = download.bakedAudioStreamIndex,
                        ).first { it.type == DownloadFileType.MEDIA }
                val retried = publisher.file.copy(url = fallback.url)
                downloadDao.updateFile(retried)
                downloadOne(publisher.forFile(retried))
            }
        }

        /**
         * Fetches one file, and — for an audio sidecar — strips the fetched mkv into the m4a the row
         * names. The row's bytes are the **m4a's**: the Downloaded tab sums file rows.
         */
        private suspend fun downloadOne(publisher: ProgressPublisher) {
            val download = publisher.download
            val file = publisher.file
            val target = storage.resolve(download.directoryName, file.fileName)
            requireStableRoot(file, target)
            if (isWholeFile(file, target)) {
                // Re-entering an item resumes at *file* granularity: a live encode can never be
                // resumed, so re-fetching a finished one would restart it from byte zero.
                publisher.alreadyWhole(target.length())
                return
            }

            downloadDao.setFileStatus(file.id, DownloadStatus.DOWNLOADING)
            val transcoded = download.isLiveEncode(file)

            withFetchFile(file, target) { fetchTarget ->
                try {
                    val fetched =
                        downloader.download(
                            url = file.url,
                            target = fetchTarget,
                            dispatcher = ioDispatcher,
                            chunkSink = publisher.sink,
                            transcoded = transcoded,
                        ) { bytes, total -> publisher.sample(bytes, total) }

                    val written =
                        if (file.type == DownloadFileType.AUDIO) strip(fetchTarget, target) else fetched

                    publisher.completed(written)
                } catch (cancellation: CancellationException) {
                    // A cancelled file keeps DOWNLOADING and its bytes; ERROR would read as a real failure.
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    downloadDao.setFileStatus(file.id, DownloadStatus.ERROR)
                    throw error
                }
            }
        }

        /**
         * `true` when this file arrives as an encode the server is performing right now. The server
         * ignores `Range` on these, so they can never be resumed.
         */
        private fun DownloadEntity.isLiveEncode(file: DownloadFileEntity): Boolean =
            quality.isTranscoded &&
                (file.type == DownloadFileType.MEDIA || file.type == DownloadFileType.AUDIO)

        /**
         * `true` when this row's file is already on disk and finished. All three legs are needed: the
         * file may have been swept, a half-stripped m4a exists without being a sidecar, and a completed
         * row's `bytesTotal` is the final on-disk size, so a mismatch means it is not that file.
         */
        private fun isWholeFile(
            file: DownloadFileEntity,
            target: File,
        ): Boolean =
            file.status == DownloadStatus.DOWNLOADED &&
                target.isFile &&
                target.length() > 0L &&
                (file.bytesTotal <= 0L || target.length() == file.bytesTotal)

        /**
         * Strips the fetched mkv into the sidecar the row names, and returns its size. A failure takes
         * the half-written m4a with it: [isWholeFile] would otherwise read it as finished. The mkv
         * belongs to [withFetchFile].
         */
        private suspend fun strip(
            part: File,
            target: File,
        ): Long {
            try {
                extractor.extract(part, target)
            } catch (cancellation: CancellationException) {
                // A cancelled strip has written part of the m4a; leaving it would occupy disk for
                // as long as the pause lasts, for a file the next attempt re-creates from its
                // first byte anyway (its row is still DOWNLOADING, so nothing reads it as whole).
                target.delete()
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                target.delete()
                throw error
            }
            return target.length()
        }

        /**
         * One file's transfer, from the progress side.
         *
         * One per **file** — the [ProgressThrottle] and [MediaChunkSink] are a file's own — while
         * [progress] is the item's, shared by the two lanes an item is drained on (see [transfer]).
         */
        private inner class ProgressPublisher(
            val download: DownloadEntity,
            val file: DownloadFileEntity,
            private val progress: ItemProgress,
            private val projector: TranscodeSizeProjector?,
            private val listener: DownloadQueueListener,
        ) {
            private val throttle = ProgressThrottle()

            val sink: MediaChunkSink? = projector?.let { MediaChunkSink(it::consume) }

            fun forFile(file: DownloadFileEntity) = ProgressPublisher(download, file, progress, projector, listener)

            suspend fun sample(
                bytes: Long,
                total: Long,
            ) {
                progress.update(file.id, bytes, total)
                val now = clock.millis()
                if (!throttle.shouldWrite(bytes, total, now)) return
                throttle.recordWrite(bytes, now)
                // A `null` projection means "no cluster yet" and must not wipe a seed.
                projector?.project(bytes)?.let { progress.mediaProjection = it }
                publish(fileBytes = bytes, fileTotal = total)
            }

            suspend fun completed(written: Long) {
                progress.update(file.id, written, written)
                if (projector != null) progress.mediaProjection = null
                downloadDao.setFileStatus(file.id, DownloadStatus.DOWNLOADED)
                publish(fileBytes = written, fileTotal = written)
            }

            suspend fun alreadyWhole(length: Long) {
                progress.update(file.id, length, length)
                publish()
            }

            /**
             * **One transaction, because `observeAll()` is one.** Room's invalidation tracker fires
             * once per commit, so two auto-commit writes per sample would re-run that whole join twice.
             *
             * @param fileBytes `null` when the publication is not about this file's byte count.
             */
            private suspend fun publish(
                fileBytes: Long? = null,
                fileTotal: Long = 0L,
            ) = progressLease.withLock {
                val snapshot = progress.snapshot()
                transactionRunner.inTransaction {
                    if (fileBytes != null) downloadDao.updateFileProgress(file.id, fileBytes, fileTotal)
                    downloadDao.updateProgress(
                        itemId = download.itemId,
                        bytesDownloaded = snapshot.bytesDownloaded,
                        bytesTotal = snapshot.bytesTotal,
                        projectedBytes = snapshot.projectedBytes,
                        updatedAt = clock.instant(),
                    )
                }
                listener.onProgress(download, snapshot.bytesDownloaded, snapshot.bytesTotal)
            }
        }

        private fun PlannedFile.toEntity(
            itemId: UUID,
            path: String,
        ) = DownloadFileEntity(
            itemId = itemId,
            type = type,
            streamIndex = streamIndex,
            tileIndex = tileIndex,
            tileWidth = tileWidth,
            fileName = fileName,
            path = path,
            url = url,
        )

        internal companion object {
            /**
             * Inside the item directory on purpose: the delete cascade unlinks the directory and
             * `OrphanSweeper` collects any directory no row claims, so a stray part file needs no rule.
             */
            const val PART_SUFFIX = ".part.mkv"

            /**
             * Five attempts on WorkManager's `EXPONENTIAL`/30 s backoff span 30+60+120+240 s — long
             * enough to sit out a server restart, short enough not to hold a foreground service all day.
             */
            const val MAX_ATTEMPTS = 5
        }
    }

/**
 * Runs a file's transfer against the file its *fetch* is written to, and deletes that file after.
 *
 * A sidecar is fetched as a video+audio mkv (the only shape the server hands a named
 * `audioStreamIndex` over in) and stored as an m4a. That fetch cannot be resumed, so the part file is
 * worthless the moment the transfer stops — stated once as a `finally` rather than in each exit arm.
 */
private inline fun <T> withFetchFile(
    file: DownloadFileEntity,
    target: File,
    block: (File) -> T,
): T {
    if (file.type != DownloadFileType.AUDIO) return block(target)
    val fetchTarget = File(target.absolutePath + DownloadQueue.PART_SUFFIX)
    try {
        return block(fetchTarget)
    } finally {
        fetchTarget.delete()
    }
}

/**
 * Fails the file when the active storage root no longer resolves to the row's own path — an SD card
 * remounted mid-transfer would split one download across two volumes, where neither the sweep, the
 * cascade nor `usedBytes()` can see the other half. Transient on purpose: the next drain re-resolves.
 */
private fun requireStableRoot(
    file: DownloadFileEntity,
    target: File,
) {
    if (target.absolutePath != file.path) {
        throw StorageUnavailableException(
            "The downloads root moved mid-item " +
                "(row has ${file.path}, the active root resolves ${target.absolutePath})",
        )
    }
}

/**
 * Running totals across an item's files, so the row's percentage is the *item's*, not the current
 * file's.
 *
 * @param estimatedTotal a floor for as long as **any** file's real size is unknown — the permanent
 *   state of a transcode, which never sends a `Content-Length`; without it `bytesTotal` would collapse
 *   onto `bytesDownloaded` and read 100 % from the first chunk.
 * @param seededProjection holds the line until the scanner has read a cluster of its own.
 *
 * The maps are concurrent because both of an item's lanes write while the other is summing.
 */
private class ItemProgress(
    files: List<DownloadFileEntity>,
    private val estimatedTotal: Long,
    seededProjection: Long? = null,
) {
    private val downloaded = ConcurrentHashMap(files.associate { it.id to it.bytesDownloaded })
    private val totals = ConcurrentHashMap(files.associate { it.id to it.bytesTotal })

    /**
     * Deliberately *not* folded into [bytesTotal]: the ceiling is a promise the enqueue step made and
     * the projection is later evidence. `@Volatile` — the ordinary lane writes it, the sidecar lane
     * reads it.
     */
    @Volatile
    var mediaProjection: Long? = seededProjection

    fun update(
        fileId: Long,
        bytes: Long,
        total: Long,
    ) {
        downloaded[fileId] = bytes
        // A file whose size the server never declared keeps its old total, so an unknown length
        // cannot drag the item's total below the bytes already written.
        if (total > 0L) totals[fileId] = total
    }

    /**
     * The three figures one publication needs, summed in **one** pass: three separate readings taken
     * while the other lane writes could disagree, and `coerceIn` throws when its bounds cross.
     */
    fun snapshot(): ProgressSnapshot {
        var downloadedSum = 0L
        for (bytes in downloaded.values) downloadedSum += bytes

        var knownTotal = 0L
        var anyUnknown = false
        for (total in totals.values) {
            knownTotal += total
            if (total <= 0L) anyUnknown = true
        }
        val bytesTotal = maxOf(knownTotal, if (anyUnknown) estimatedTotal else 0L, downloadedSum)

        // `knownTotal` is the item's *other* files while the media file's length is unknown. Clamped
        // so a projection claims neither less than what landed nor more than the ceiling.
        val projected = mediaProjection?.let { (it + knownTotal).coerceIn(downloadedSum, bytesTotal) }

        return ProgressSnapshot(downloadedSum, bytesTotal, projected)
    }
}

private data class ProgressSnapshot(
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val projectedBytes: Long?,
)
