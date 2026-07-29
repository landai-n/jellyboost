package dev.jellyfinnative.data.downloads.engine

import dev.jellyfinnative.core.common.model.DownloadFileType
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.DownloadEntity
import dev.jellyfinnative.core.database.entities.DownloadFileEntity
import dev.jellyfinnative.core.database.entities.DownloadWithFiles
import dev.jellyfinnative.core.network.di.IoDispatcher
import dev.jellyfinnative.core.network.session.SessionGate
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.plan.DownloadFilePlanner
import dev.jellyfinnative.data.downloads.plan.PlannedFile
import dev.jellyfinnative.data.downloads.storage.DownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the queue tells its host (the worker) as it moves through an item. */
interface DownloadQueueListener {
    /** A new item started, or its progress advanced. Called on every throttled Room write. */
    suspend fun onProgress(
        download: DownloadEntity,
        bytesDownloaded: Long,
        bytesTotal: Long,
    )

    /** Nothing is downloading any more — the queue drained, or was stopped. */
    suspend fun onIdle()
}

/**
 * How a drain ended.
 *
 * The distinction the worker cares about is [NO_SESSION] versus the other two: a queue that could
 * not run is not a queue that ran badly, and it must be re-tried rather than reported.
 */
enum class DrainOutcome {
    /** Everything runnable finished. */
    COMPLETED,

    /** The queue ran, and at least one item failed; its row carries the reason. */
    INCOMPLETE,

    /**
     * Nothing was attempted because this device has no usable session. No row was touched, so no
     * item is left claiming a failure it never had.
     */
    NO_SESSION,
}

/** An item whose cached `BaseItemDto` is gone — the file plan cannot be built without it. */
internal class MissingMetadataException(
    itemId: UUID,
) : IllegalStateException("No cached metadata for $itemId")

/**
 * Runs the download queue: one item at a time, in `queuePosition` order, until nothing runnable is
 * left (docs/PLAN.md, "Download pipeline").
 *
 * ### Why one at a time
 * It matches the unique-work model (`enqueueUniqueWork("downloads", KEEP)`), it makes the "which
 * item is downloading" question have exactly one answer for the notification and the badges, and on
 * a single home connection two parallel transfers finish no sooner than two sequential ones while
 * doubling the number of half-written files a kill can leave behind.
 *
 * ### Failure policy
 * The plan's essential/optional split is the whole of it. The media file failing marks the item
 * [DownloadStatus.ERROR] and moves on to the next item. Any other file failing marks *that file*
 * ERROR, is logged, and is otherwise ignored — a film without its backdrop is still a film.
 *
 * ### Cancellation
 * A cancelled coroutine (the user paused, WorkManager withdrew the network constraint, the process
 * is going away) leaves the partial file exactly where it is and the row back in
 * [DownloadStatus.QUEUED] — *unless* something already gave it another status, which is what a
 * pause does. The next run resumes it from its byte offset, which is the property the milestone's
 * definition of done measures.
 *
 * ### The session
 * Nothing here can build a URL until the API client knows its server, and on a cold start this
 * runs before the UI has restored anything — see [SessionGate]. The gate is consulted once per
 * drain, before any row is touched.
 */
@Singleton
class DownloadQueue
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val itemDao: ItemDao,
        private val itemMapper: ItemEntityMapper,
        private val planner: DownloadFilePlanner,
        private val storage: DownloadStorage,
        private val downloader: FileDownloader,
        private val sessionGate: SessionGate,
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Drains the queue.
         *
         * @return [DrainOutcome.COMPLETED] when everything runnable finished,
         *   [DrainOutcome.INCOMPLETE] when an item failed (its row says why), or
         *   [DrainOutcome.NO_SESSION] when there is nothing to run *with* — the worker retries that
         *   instead of leaving items marked failed.
         */
        suspend fun drain(listener: DownloadQueueListener): DrainOutcome {
            // A row left DOWNLOADING belongs to a process that no longer exists. Putting it back in
            // the queue is what lets `nextRunnable` tell "mine" from "someone else's" — and it runs
            // before the session gate so that a parked queue still reads as "Waiting" rather than
            // showing a transfer that no process is performing.
            downloadDao.requeueInterrupted(clock.instant())

            if (!sessionGate.ensureSession()) {
                listener.onIdle()
                return DrainOutcome.NO_SESSION
            }

            var allSucceeded = true
            while (true) {
                val next = downloadDao.nextRunnable() ?: break
                allSucceeded = process(next, listener) && allSucceeded
            }
            listener.onIdle()
            return if (allSucceeded) DrainOutcome.COMPLETED else DrainOutcome.INCOMPLETE
        }

        private suspend fun process(
            queued: DownloadWithFiles,
            listener: DownloadQueueListener,
        ): Boolean {
            val download = queued.download
            downloadDao.setStatus(download.itemId, DownloadStatus.DOWNLOADING, clock.instant())

            return try {
                val dto = loadDto(download) ?: throw MissingMetadataException(download.itemId)
                val files = reconcile(download, dto, queued.files)
                // `false` means the row was deleted underneath us (the user cancelled): the item is
                // gone, not finished, and writing a status for it would only resurrect nothing.
                if (transfer(download, dto, files, listener)) {
                    downloadDao.setStatus(download.itemId, DownloadStatus.DOWNLOADED, clock.instant())
                }
                true
            } catch (cancellation: CancellationException) {
                // Not a failure: put the row back so the next run resumes it, then let the
                // cancellation continue to unwind. `NonCancellable` because a suspending Room write
                // inside an already-cancelled coroutine would itself be cancelled, and the row
                // would stay `DOWNLOADING` for a process that is going away.
                //
                // Conditional on the row still being DOWNLOADING, because the most common cause of
                // this cancellation is the user pressing *Pause*, which writes `PAUSED` and then
                // cancels the work: an unconditional re-queue here would undo their own request.
                withContext(NonCancellable) {
                    downloadDao.requeueIfDownloading(download.itemId, clock.instant())
                }
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.e(error, "Download of %s failed", download.itemName)
                downloadDao.setStatus(
                    itemId = download.itemId,
                    status = DownloadStatus.ERROR,
                    updatedAt = clock.instant(),
                    // Deliberately not `error.message`: that string is rendered to the user, and
                    // exception text is written for a log file (see [DownloadErrorCopy]).
                    errorMessage = DownloadErrorCopy.forFailure(error),
                )
                false
            }
        }

        /** Reads back the full `BaseItemDto` the enqueue step cached with `source = DOWNLOAD`. */
        private suspend fun loadDto(download: DownloadEntity): BaseItemDto? =
            itemDao.getItem(download.itemId)?.let(itemMapper::toDtoOrNull)

        /**
         * Brings the stored file rows in line with a freshly-built plan, and returns them in plan
         * order.
         *
         * ### What is re-planned, and what is not
         * The URL is rebuilt on every run: it embeds the server's base address, and
         * `ServerReachabilityProbe` rotates that between LAN and remote, so a row queued at home and
         * run on mobile data must be fetched from the address that answers *now*.
         *
         * The **file name is not**. When a row already exists it keeps the name it was created
         * with, because that name *is* the partial file on disk and the plan cannot be trusted to
         * produce it twice: `DownloadPaths.mediaFileName` prefers the server's own filename from
         * `BaseItemDto.path`, and `path` is only returned to users allowed to see it and is absent
         * from some cached shapes of the DTO. On the M7 device walk that difference renamed a
         * half-finished 1.38 GB film from `Backrooms.2026…-BATGirl.mkv` to `Backrooms (2026).mkv`
         * on the retry, orphaning the partial file and restarting the transfer from zero — the
         * opposite of the milestone's resume guarantee. Room holds the file plan; Room wins
         * (docs/PLAN.md, "Room = single source of truth").
         *
         * ### When a genuine re-plan happens
         * Only when there are no rows: a first attempt, or an item re-enqueued after a delete (the
         * cascade removes `download_files` through the foreign key *and* the directory, so there is
         * no file left for a stale name to point at). Re-planning is therefore free precisely when
         * it is safe.
         *
         * Rows are matched on (type, stream, tile) — the same key as the table's unique index — and
         * updated in place, so the bytes already on disk keep their row and its resume offset. A
         * stored row the new plan no longer contains (a subtitle track removed on the server) is
         * left alone: it is not downloaded again, and the delete cascade will collect it.
         */
        private suspend fun reconcile(
            download: DownloadEntity,
            dto: BaseItemDto,
            existing: List<DownloadFileEntity>,
        ): List<DownloadFileEntity> {
            storage.prepareItemDirectory(download.directoryName)
            val planned = planner.plan(dto, download.directoryName)

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
                    // The name comes from the row, the address from the plan. The path is
                    // re-resolved from the *stored* name so that a change of storage root still
                    // takes effect without renaming the file.
                    val path = storage.resolve(download.directoryName, previous.fileName).absolutePath
                    val row = previous.copy(path = path, url = file.url)
                    downloadDao.updateFile(row)
                    row
                }
            }
        }

        /**
         * Fetches an item's files in plan order.
         *
         * @return `false` when the item's row disappeared mid-transfer. The delete cascade runs
         *   while this coroutine may still be alive (WorkManager's cancellation is asynchronous),
         *   and `FileDownloader` re-creates the item directory for every file it opens — so without
         *   this check a cancel landing between two files would leave a freshly-written directory
         *   that no Room row points at, which is unreachable from the UI and therefore leaked.
         */
        private suspend fun transfer(
            download: DownloadEntity,
            dto: BaseItemDto,
            files: List<DownloadFileEntity>,
            listener: DownloadQueueListener,
        ): Boolean {
            val progress = ItemProgress(files)

            for (file in files) {
                if (downloadDao.get(download.itemId) == null) {
                    Timber.i("%s was removed while it was downloading; stopping", download.itemName)
                    return false
                }

                if (file.type.essential) {
                    downloadEssential(download, dto, file, progress, listener)
                } else {
                    runCatching { downloadOne(download, file, progress, listener) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            Timber.w(error, "Optional file %s failed; item stays playable", file.fileName)
                        }
                }
            }
            return true
        }

        /**
         * The media file, with the plan's download-policy fallback.
         *
         * A `403` here means the server refused `/Items/{id}/Download` for this user, which is
         * exactly the "download policy denied" case; the same bytes are still reachable as a static
         * video stream, so the one file is re-planned onto that URL and retried once.
         */
        private suspend fun downloadEssential(
            download: DownloadEntity,
            dto: BaseItemDto,
            file: DownloadFileEntity,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ) {
            try {
                downloadOne(download, file, progress, listener)
            } catch (error: DownloadHttpException) {
                if (error.code != HTTP_FORBIDDEN) throw error

                Timber.i("Download endpoint denied for %s; falling back to the video stream", download.itemName)
                val fallback =
                    planner
                        .plan(dto, download.directoryName, downloadAllowed = false)
                        .first { it.type == DownloadFileType.MEDIA }
                val retried = file.copy(url = fallback.url)
                downloadDao.updateFile(retried)
                downloadOne(download, retried, progress, listener)
            }
        }

        private suspend fun downloadOne(
            download: DownloadEntity,
            file: DownloadFileEntity,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ) {
            val target = storage.resolve(download.directoryName, file.fileName)
            downloadDao.setFileStatus(file.id, DownloadStatus.DOWNLOADING)
            val throttle = ProgressThrottle()

            try {
                val written =
                    downloader.download(file.url, target, ioDispatcher) { bytes, total ->
                        progress.update(file.id, bytes, total)
                        val now = clock.millis()
                        if (throttle.shouldWrite(bytes, total, now)) {
                            throttle.recordWrite(bytes, now)
                            downloadDao.updateFileProgress(file.id, bytes, total)
                            publish(download, progress, listener)
                        }
                    }

                progress.update(file.id, written, written)
                downloadDao.updateFileProgress(file.id, written, written)
                downloadDao.setFileStatus(file.id, DownloadStatus.DOWNLOADED)
                publish(download, progress, listener)
            } catch (cancellation: CancellationException) {
                // A cancelled file keeps its DOWNLOADING status and its bytes; the next run picks
                // both up again. Marking it ERROR here would look like a real failure.
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                downloadDao.setFileStatus(file.id, DownloadStatus.ERROR)
                throw error
            }
        }

        private suspend fun publish(
            download: DownloadEntity,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ) {
            downloadDao.updateProgress(
                itemId = download.itemId,
                bytesDownloaded = progress.bytesDownloaded,
                bytesTotal = progress.bytesTotal,
                updatedAt = clock.instant(),
            )
            listener.onProgress(download, progress.bytesDownloaded, progress.bytesTotal)
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

        private companion object {
            const val HTTP_FORBIDDEN = 403
        }
    }

/**
 * Running totals across an item's files, so the row's percentage is the *item's* and not the
 * current file's.
 *
 * Without this a 2 GB film would jump to 100 % while its 40 KB poster finished, then back to 0 %.
 * Sizes start from the rows already on disk so a resumed item does not restart its percentage.
 */
private class ItemProgress(
    files: List<DownloadFileEntity>,
) {
    private val downloaded = files.associate { it.id to it.bytesDownloaded }.toMutableMap()
    private val totals = files.associate { it.id to it.bytesTotal }.toMutableMap()

    fun update(
        fileId: Long,
        bytes: Long,
        total: Long,
    ) {
        downloaded[fileId] = bytes
        // A file whose size the server never declared keeps whatever total it already had, so an
        // unknown length cannot drag the item's total below the bytes already written.
        if (total > 0L) totals[fileId] = total
    }

    val bytesDownloaded: Long get() = downloaded.values.sum()

    val bytesTotal: Long get() = maxOf(totals.values.sum(), bytesDownloaded)
}
