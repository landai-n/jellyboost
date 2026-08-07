package dev.jellyboost.data.downloads.engine

import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.model.DownloadFileType
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadFileEntity
import dev.jellyboost.core.database.entities.DownloadWithFiles
import dev.jellyboost.core.network.di.IoDispatcher
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
 * The distinction the worker cares about is between the outcomes it *reports* ([COMPLETED],
 * [INCOMPLETE]) and the ones it must *re-run* ([RETRY], [NO_SESSION]): a queue that could not run,
 * or one that stopped on something that may work in a minute, is not a queue that ran badly.
 */
enum class DrainOutcome {
    /** Everything runnable finished. */
    COMPLETED,

    /** The queue ran, and at least one item failed permanently; its row carries the reason. */
    INCOMPLETE,

    /**
     * An item failed in a way that another attempt may fix, and the drain stopped there.
     *
     * The rest of the queue is untouched and still `QUEUED`: the point of stopping is that whatever
     * made the first item fail — a restarting server, a proxy answering `502`, a VPN handover — is
     * about to do the same to every row behind it (docs/notes/audit-2026-07.md, STAB-01).
     */
    RETRY,

    /**
     * Nothing was attempted because this device has no usable session. No row was touched, so no
     * item is left claiming a failure it never had.
     */
    NO_SESSION,
}

/** How one item's turn ended — [DrainOutcome] for a single row. */
private enum class ItemOutcome { SUCCEEDED, FAILED, RETRY }

/** An item whose cached `BaseItemDto` is gone — the file plan cannot be built without it. */
internal class MissingMetadataException(
    itemId: UUID,
) : IllegalStateException("No cached metadata for $itemId")

/**
 * Runs the download queue: one item at a time, in `queuePosition` order, until nothing runnable is
 * left (docs/PLAN.md, "Download pipeline").
 *
 * ### Why one item at a time
 * It matches the unique-work model (`enqueueUniqueWork("downloads", KEEP)`), it makes the "which
 * item is downloading" question have exactly one answer for the notification and the badges, and on
 * a single home connection two parallel transfers finish no sooner than two sequential ones while
 * doubling the number of half-written files a kill can leave behind.
 *
 * *Inside* one item there is exactly one exception, and it is not a bandwidth bet: an extra audio
 * language is a live server transcode whose wire rate is its own stream's bitrate, so it does not
 * compete with the media file for the link — see [transfer].
 *
 * ### Failure policy
 * The plan's essential/optional split decides *which* failures matter: any file other than the
 * media file failing marks *that file* ERROR, is logged, and is otherwise ignored — a film without
 * its backdrop is still a film.
 *
 * The media file failing is then classified (see [DownloadFailureClassifier]). A **permanent**
 * failure marks the item [DownloadStatus.ERROR] and the drain moves on to the next item. A
 * **transient** one leaves the row `QUEUED` with its [DownloadEntity.attemptCount] raised and stops
 * the drain, so the worker can ask WorkManager for a retry on its existing exponential backoff; the
 * row only reaches ERROR once it has spent [MAX_ATTEMPTS]. Carrying on past a transient failure is
 * what used to turn one server blip into a queue of forty failed episodes
 * (docs/notes/audit-2026-07.md, STAB-01).
 *
 * ### One drain at a time
 * [drain] holds a process-wide lease. Two drains overlapping is not hypothetical: every
 * `ExistingWorkPolicy.REPLACE` enqueue starts the new worker while the old one is still unwinding,
 * and both of them would run `requeueInterrupted` — the second claiming the very row the first
 * still holds a `RandomAccessFile` on (docs/notes/audit-2026-07.md, STAB-09). Serialising them is
 * also what makes "one item at a time" a property of the *process* rather than of one drain.
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
internal class DownloadQueue
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
        private val clock: Clock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Held for the length of a drain; see "One drain at a time" above.
         *
         * A lease rather than a database column because there is exactly one app process: a column
         * could record *who* claimed a row but nothing in the schema can say whether that claimant
         * is still alive, and a time-based lease has to choose between stealing a row from a
         * stalled-but-live transfer and making resume-after-a-crash wait out the window.
         */
        private val drainLease = Mutex()

        /**
         * Held across one progress publication, so an item's two lanes (see [transfer]) never make
         * one at the same moment.
         *
         * [publish] is a read of the shared [ItemProgress] followed by a Room write *and* a call
         * into the host's listener, and that listener keeps state of its own: `DownloadNotifier`
         * remembers the figure it last posted so it can skip a re-post nothing would render. One
         * lease turns two lanes back into a single writer for that instant — neither the row nor
         * the notification can be left carrying a sample the other lane already overtook, and
         * nothing outside this file has to learn about the second lane. It is held for one `UPDATE`
         * and one notification comparison, a handful of times a second at worst.
         */
        private val progressLease = Mutex()

        /**
         * Drains the queue.
         *
         * Suspends while another drain holds the lease — which is the point: a `REPLACE` enqueue
         * starts this worker before the previous one has finished unwinding, and a cancelled drain
         * releases the lease as it goes.
         *
         * @return [DrainOutcome.COMPLETED] when everything runnable finished,
         *   [DrainOutcome.INCOMPLETE] when an item failed permanently (its row says why),
         *   [DrainOutcome.RETRY] when it stopped on a failure worth another attempt, or
         *   [DrainOutcome.NO_SESSION] when there is nothing to run *with* — the worker re-runs the
         *   last two instead of leaving items marked failed.
         */
        suspend fun drain(listener: DownloadQueueListener): DrainOutcome =
            drainLease.withLock { drainExclusively(listener) }

        private suspend fun drainExclusively(listener: DownloadQueueListener): DrainOutcome {
            // A row left DOWNLOADING belongs to a process that no longer exists. Putting it back in
            // the queue is what lets `nextRunnable` tell "mine" from "someone else's" — and it runs
            // before the session gate so that a parked queue still reads as "Waiting" rather than
            // showing a transfer that no process is performing.
            downloadDao.requeueInterrupted(clock.instant())
            sweeper.sweep()

            if (!sessionGate.ensureSession()) {
                listener.onIdle()
                return DrainOutcome.NO_SESSION
            }

            var outcome = DrainOutcome.COMPLETED
            // Stopping on RETRY is not optional: a retried row is `QUEUED` again, so `nextRunnable`
            // would hand back the same item forever. It is also the point — whatever failed is
            // about to fail for every row behind it.
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
            // A guarded claim, not a plain write: `pause`/`pauseAll` write PAUSED before stopping
            // the worker, and a drain sitting between `nextRunnable()` and this line used to
            // clobber that with DOWNLOADING — the cancellation then re-queued the row and the item
            // the user had just paused downloaded anyway (audit DL-03). Zero rows updated means
            // the row changed hands since it was picked; leave it exactly as its new owner put it.
            if (downloadDao.markDownloadingIfRunnable(download.itemId, clock.instant()) == 0) {
                Timber.i("%s changed status before its transfer began; leaving it alone", download.itemName)
                return ItemOutcome.SUCCEEDED
            }

            return try {
                val dto = loadDto(download) ?: throw MissingMetadataException(download.itemId)
                val seeded = seedIfUnseeded(download, dto)
                val files = reconcile(seeded, dto, queued.files)
                // `false` means the row was deleted underneath us (the user cancelled): the item is
                // gone, not finished, and writing a status for it would only resurrect nothing.
                if (transfer(seeded, dto, files, listener)) {
                    downloadDao.setStatus(seeded.itemId, DownloadStatus.DOWNLOADED, clock.instant())
                    reseedSiblings(seeded)
                }
                ItemOutcome.SUCCEEDED
            } catch (cancellation: CancellationException) {
                // Not a failure, and never a retry: put the row back so the next run resumes it,
                // then let the cancellation continue to unwind. `NonCancellable` because a
                // suspending Room write inside an already-cancelled coroutine would itself be
                // cancelled, and the row would stay `DOWNLOADING` for a process that is going away.
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
                fail(download, error)
            }
        }

        /**
         * Records a failed attempt, and says whether the item deserves another one.
         *
         * The attempt counter lives on the row rather than in memory because the retry is performed
         * by a *new* worker run — the process may not even be the same one.
         */
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
                // Deliberately not `error.message`: that string is rendered to the user, and
                // exception text is written for a log file (see [DownloadErrorCopy]).
                errorMessage = DownloadErrorCopy.forFailure(error),
            )
            return ItemOutcome.FAILED
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
            // The quality comes from the row, never from the live preference: the bytes already on
            // disk were fetched at it (DECISIONS.md, 2026-07-29). The baked audio track comes from
            // the row for the same reason and one more: the DTO's default audio stream is the
            // server's *current* answer, and re-deriving it here would silently re-plan a
            // half-downloaded transcode onto a different track if the server's metadata moved
            // between the tap and the drain. `bakedAudioStreamIndex` is what the enqueue actually
            // asked for, and it is null exactly when there was no pin to make — an ORIGINAL row,
            // or an item with no audio streams — which is the same thing the planner does with an
            // absent index (DECISIONS.md, 2026-07-30).
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
         * Fetches an item's files: the ordinary ones in plan order, its audio sidecars alongside
         * them.
         *
         * ### Two lanes
         * An extra audio language is a live `/Videos` transcode of one stream, so what arrives over
         * the wire is that stream's bitrate multiplied by the server's encoding speed — a few
         * hundred KB/s no matter how good the link is. Draining those rows *after* the media file
         * therefore added their whole duration to the item's: two tracks cost about eleven minutes
         * after the film itself had finished, on the first device walk. Run alongside the media
         * file the same minutes disappear into it, and an item costs `max(media, sidecars)` rather
         * than their sum (DECISIONS.md, 2026-07-31, "Audio sidecars fetch concurrently with the
         * media file").
         *
         * **At most two live encodes per item, by construction.** The sidecar lane runs its own
         * rows strictly sequentially, and the only other transcode in the plan is the media file
         * the ordinary lane is on — so the server is never asked for a third, and the CPU it does
         * not spend on sidecars stays with the encode the user is actually waiting for.
         *
         * ### What a failure costs, unchanged
         * A sidecar that fails marks its own row [DownloadStatus.ERROR] and its lane carries on to
         * the next language — the same optional-file rule the ordinary lane applies to a subtitle.
         * The media file failing throws out of this scope, which cancels the lane where it stands:
         * a *cancelled* sidecar is not a failed one, so its row keeps `DOWNLOADING` and its
         * unresumable fetch is deleted ([downloadOne]), leaving the retry a row to re-plan from
         * scratch rather than an `ERROR` to explain. Either way the item is finished only once
         * **both** lanes are, which [coroutineScope] is what guarantees.
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
        ): Boolean =
            coroutineScope {
                val progress =
                    ItemProgress(
                        files,
                        estimatedTotal = download.bytesTotal,
                        seededProjection = download.projectedBytes,
                    )
                // `partition` keeps plan order on both sides, which is what makes the sidecar lane
                // ascend by stream index without sorting anything.
                val (sidecars, ordinary) = files.partition { it.type == DownloadFileType.AUDIO }
                val lane = launch { drainSidecars(download, sidecars, progress, listener) }

                val alive = drainOrdinary(download, dto, ordinary, progress, listener)
                // The row is gone, which is not a failure and so does not cancel the scope by
                // itself. Stop the lane anyway: every file it opens re-creates the item directory
                // the cascade has just unlinked.
                if (!alive) lane.cancel()
                alive
            }

        /**
         * Everything but the audio sidecars, in plan order — the lane the queue has always had.
         *
         * @return `false` when the item's row disappeared mid-transfer; see [transfer].
         */
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

                // Only the media file is Matroska, and only it is worth projecting: everything else
                // in this lane is artwork and subtitles whose sizes the server does declare.
                // An audio sidecar is a transcode too and declares no length either, but its share
                // of the item is a term of the enqueue-time ceiling (`DownloadEnqueuer.sizeEstimate`)
                // rather than something to project: one projector per item measures the media file.
                val fileProjector = projector.takeIf { file.type == DownloadFileType.MEDIA }
                val publisher = ProgressPublisher(download, file, progress, fileProjector, listener)
                if (file.type.essential) {
                    downloadEssential(dto, publisher)
                } else {
                    // try/catch(Exception), not runCatching: the latter catches Throwable, so an
                    // OutOfMemoryError from an optional file would be logged as "optional file
                    // failed" and the drain would carry on in an undefined state (audit DL-12).
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

        /**
         * The audio lane: every sidecar of this item, one after another, alongside [drainOrdinary].
         *
         * Sequential on purpose — see [transfer] for the two-encodes-per-item ceiling that keeps
         * the server's CPU on the media file. No projector is ever passed: a sidecar declares no
         * length, but the item's one [TranscodeSizeProjector] measures the media file and a second
         * scanner reading a second stream would only fight it for the same row.
         *
         * A cancellation leaves the loop *without* recording anything: it means the item failed or
         * was withdrawn, and both lanes are unwinding together.
         */
        private suspend fun drainSidecars(
            download: DownloadEntity,
            files: List<DownloadFileEntity>,
            progress: ItemProgress,
            listener: DownloadQueueListener,
        ) {
            for (file in files) {
                // The same guard the ordinary lane keeps, for the same reason: this lane opens
                // files of its own, and the delete cascade can land between any two of them.
                if (downloadDao.get(download.itemId) == null) return

                // try/catch(Exception) for the same reason as the ordinary lane (audit DL-12).
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
         * Passes a finished item's measured size on to the rows still waiting on the same show.
         *
         * Called once the row is `DOWNLOADED`, so it is itself part of the evidence — a season
         * queued in one tap has no other way to ever leave its enqueue-time ceiling, since seeding
         * at enqueue had nothing finished to learn from.
         *
         * Best effort on purpose: a download that is on disk and playable must not be reported as
         * failed because a cosmetic estimate for the *next* item could not be written.
         */
        private suspend fun reseedSiblings(download: DownloadEntity) {
            runCatching { seeder.seedPendingSiblingsOf(download) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.w(error, "Could not re-seed the siblings of %s", download.itemName)
                }
        }

        /**
         * Gives a row about to start a projection if it still has none, and returns the row the
         * transfer should run with.
         *
         * The scanner cannot say anything until a first Matroska cluster has arrived, which on a
         * slow connection is tens of seconds of the user staring at *"up to X"* for an episode whose
         * siblings are sitting finished on the same device. The row may well have been enqueued
         * before any of them landed — a whole season queued in one tap is exactly that case — so the
         * seed is computed again here rather than trusted to have happened at enqueue.
         *
         * The write goes through [DownloadDao.setProjectedBytesIfAbsent], so a projection written in
         * the meantime wins; the returned row carries the seed so the in-memory `ItemProgress`
         * starts from it too, instead of waiting for the next drain to pick it up from Room.
         */
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
         * The live size projection for this item's media file, or `null` when there is nothing to
         * project.
         *
         * Three ways to get `null`, and each is a case where the projection would be worse than
         * what the row already says:
         * - **`ORIGINAL`** — the total is the server's own file size, exact, already.
         * - **`sizeIsExact`** — the enqueue step recognised the request as a video stream copy, so
         *   `bytesTotal` is a prediction of the actual file rather than a ceiling. Letting the
         *   scanner second-guess it would flip a plain figure to an approximate one mid-transfer for
         *   no gain.
         * - **no runtime** — there is nothing to extrapolate the observed bitrate *to*.
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
         * The media file, with the plan's download-policy fallback.
         *
         * A `403` here means the server refused `/Items/{id}/Download` for this user, which is
         * exactly the "download policy denied" case; the same bytes are still reachable as a static
         * video stream, so the one file is re-planned onto that URL and retried once.
         *
         * The fallback is for `ORIGINAL` downloads only. A transcoded row never asks
         * `/Items/{id}/Download` in the first place, so a `403` on one is a real refusal and
         * re-planning would only re-issue the identical URL.
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
                // A second attempt is a second transfer: it gets a publisher of its own, so its
                // throttle starts where the first one's did rather than mid-cadence.
                downloadOne(publisher.forFile(retried))
            }
        }

        /**
         * Fetches one file of the plan, and — for an audio sidecar — strips it into the shape the
         * row names.
         *
         * ### The audio sidecar's two files
         * An extra audio language is *fetched* as a video+audio mkv and *stored* as an audio-only
         * m4a — [withFetchFile] owns the fetch file and the rule about its lifetime; what happens
         * here is the [strip] between the two.
         *
         * The row's bytes are the **m4a's**, not the mkv's: the Downloaded tab sums file rows, and
         * a row still claiming the junk video's size would overstate the item by the larger of the
         * two numbers for as long as it exists on disk.
         */
        private suspend fun downloadOne(publisher: ProgressPublisher) {
            val download = publisher.download
            val file = publisher.file
            val target = storage.resolve(download.directoryName, file.fileName)
            requireStableRoot(file, target)
            if (isWholeFile(file, target)) {
                // Already on disk and finished by an earlier run — re-entering the item must
                // resume at *file* granularity. This matters most for a transcoded media file: an
                // interruption during the sidecar tail (a lane that runs for minutes after the
                // film itself finished) re-queues the whole item, and without this guard the
                // completed multi-gigabyte encode was truncated and re-fetched from byte zero,
                // because a live encode can never be resumed (audit DL-02). A finished sidecar is
                // the same case — its fetch cannot be resumed either.
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
                    // A cancelled file keeps its DOWNLOADING status and its bytes; the next run
                    // picks both up again. Marking it ERROR here would look like a real failure —
                    // and for a sidecar it would be one the retry never clears, since the audio
                    // lane is cancelled by design whenever the media file fails (see [transfer]).
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
         * `true` when this file arrives as an encode the server is performing right now.
         *
         * Two of them, and they are exactly the two lanes' transcodes: the media file of a
         * transcoded row, and every audio sidecar of one (a `/Videos` transcode of its own). The
         * server ignores `Range` on both, so neither may be resumed — a second encode's bytes
         * appended to a first one's prefix is a file that opens and is wrong. Images and subtitles
         * are ordinary files the server already holds, and they resume like any other.
         */
        private fun DownloadEntity.isLiveEncode(file: DownloadFileEntity): Boolean =
            quality.isTranscoded &&
                (file.type == DownloadFileType.MEDIA || file.type == DownloadFileType.AUDIO)

        /**
         * `true` when this row's file is already on disk and finished.
         *
         * The row's status alone is not enough (the file may have been swept, or the volume
         * remounted empty) and the file alone is not either: a strip interrupted halfway leaves an
         * m4a that exists and is not a sidecar. Both, and the row is left where it is.
         *
         * The size check is the third leg: a completed row's `bytesTotal` was written as the final
         * on-disk size (`updateFileProgress(id, written, written)`), so a file that no longer
         * matches it — truncated, or replaced by something else — is not the file the row
         * describes and must be fetched again. Rows whose total was never known keep the
         * existence-plus-non-empty test alone.
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
         * Strips the fetched mkv into the sidecar the row names, and returns the sidecar's size.
         *
         * A failure takes the half-written m4a with it: a part-written sidecar is worse than none
         * at all, since [isWholeFile] would read it as a finished one and `DownloadedMediaProvider`
         * would offer a truncated audio track to the player. The exception then travels the
         * ordinary optional-file route — file row `ERROR`, item still `DOWNLOADED` — exactly as a
         * failed subtitle does.
         *
         * The mkv itself is not this function's to delete however this ends: it is the fetch file,
         * and [withFetchFile] owns it.
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
         * One file's transfer, from the progress side: everything the 64 KB callback used to do
         * inline, as a value the transfer is handed instead of five parameters.
         *
         * Five concerns lived in that fourteen-line lambda and were forwarded, unchanged, through
         * two signatures to reach it (docs/notes/audit-2026-08-06-quality.md, CPX-12): the item's
         * running totals, the 500 ms/1 % write throttle, the live size projection, the file's own
         * row, and the host's notification. They are one object now, and the transfer's own
         * signatures name a *file* and a publisher rather than the publisher's parts.
         *
         * One per **file**, not per item: the [ProgressThrottle] and the [MediaChunkSink] are a
         * file's own (a throttle carried across files would let one file's cadence decide the
         * next one's first sample), while [progress] is deliberately the item's — it is what makes
         * a card's percentage the item's and not the current file's, and it is shared by the two
         * lanes an item is drained on (see [transfer]).
         *
         * @param projector `null` for every file but a transcoded row's media file — see
         *   [drainOrdinary] for why one projector per item is the right number.
         */
        private inner class ProgressPublisher(
            val download: DownloadEntity,
            val file: DownloadFileEntity,
            private val progress: ItemProgress,
            private val projector: TranscodeSizeProjector?,
            private val listener: DownloadQueueListener,
        ) {
            private val throttle = ProgressThrottle()

            /**
             * The tap on the body the projector reads its Matroska clusters from, or `null` when
             * nothing is projecting this file. Held here so the transfer never has to know why.
             */
            val sink: MediaChunkSink? = projector?.let { MediaChunkSink(it::consume) }

            /** The same publisher's collaborators, pointed at a re-planned [DownloadFileEntity]. */
            fun forFile(file: DownloadFileEntity) = ProgressPublisher(download, file, progress, projector, listener)

            /** One 64 KB callback: always counted, written only on the throttle's cadence. */
            suspend fun sample(
                bytes: Long,
                total: Long,
            ) {
                progress.update(file.id, bytes, total)
                val now = clock.millis()
                if (!throttle.shouldWrite(bytes, total, now)) return
                throttle.recordWrite(bytes, now)
                // Recomputed on the existing throttle cadence, not per 64 KB callback: the
                // projection is only as fresh as the row it is written to. A `null` here means
                // "no cluster yet" and must not wipe a seed.
                projector?.project(bytes)?.let { progress.mediaProjection = it }
                downloadDao.updateFileProgress(file.id, bytes, total)
                publish()
            }

            /** The file is whole at [written] bytes, and its row can say so. */
            suspend fun completed(written: Long) {
                progress.update(file.id, written, written)
                // The file is whole, so its size is no longer a question: drop the projection and
                // let the exact sum of real sizes speak.
                if (projector != null) progress.mediaProjection = null
                downloadDao.updateFileProgress(file.id, written, written)
                downloadDao.setFileStatus(file.id, DownloadStatus.DOWNLOADED)
                publish()
            }

            /**
             * The file was already finished on disk when the item was re-entered: its bytes join
             * the item's total, and nothing about the row itself changes.
             */
            suspend fun alreadyWhole(length: Long) {
                progress.update(file.id, length, length)
                publish()
            }

            /** One progress sample to Room and to the host, under [progressLease]. */
            private suspend fun publish() =
                progressLease.withLock {
                    val bytesDownloaded = progress.bytesDownloaded
                    val bytesTotal = progress.bytesTotal
                    downloadDao.updateProgress(
                        itemId = download.itemId,
                        bytesDownloaded = bytesDownloaded,
                        bytesTotal = bytesTotal,
                        projectedBytes = progress.projectedBytes,
                        updatedAt = clock.instant(),
                    )
                    listener.onProgress(download, bytesDownloaded, bytesTotal)
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
             * What an audio sidecar's *fetch* is written to, next to the sidecar itself.
             *
             * Inside the item directory on purpose: the delete cascade unlinks the whole directory
             * and `OrphanSweeper` collects any directory no row claims, so a part file left by a
             * process death is swept with everything else and never needs a rule of its own.
             */
            const val PART_SUFFIX = ".part.mkv"

            /**
             * How many attempts a transient failure is worth before the row is called failed.
             *
             * Each retry is a fresh worker run on WorkManager's existing `EXPONENTIAL`/30 s
             * backoff, so five attempts span 30 s + 60 s + 120 s + 240 s — a little over seven
             * minutes. Long enough to sit out a server restart, a proxy blip or a VPN handover;
             * short enough that an address which is simply gone does not keep a foreground service
             * alive all afternoon.
             */
            const val MAX_ATTEMPTS = 5
        }
    }

/**
 * Runs a file's transfer against the file its *fetch* is written to, and takes that file with it.
 *
 * ### The rule, stated once
 * A sidecar's fetch cannot be resumed, so its part file is worthless the moment the transfer stops
 * needing it. An extra audio language is fetched as a video+audio mkv — the only shape the server
 * will hand a named `audioStreamIndex` over in — and stored as an audio-only m4a, so the fetch
 * lands beside the sidecar as `<name>.part.mkv` and is stripped into place
 * (DECISIONS.md, 2026-07-31, "Offline multi-track Phase 2"). Whatever ends the transfer, that mkv
 * has no future: a strip consumed it, a failure cannot resume it, and a cancellation would leave
 * hundreds of megabytes of junk video in the item's directory for as long as the pause lasts —
 * possibly a week. The next attempt truncates it from byte zero either way, because the fetch is
 * flagged un-resumable.
 *
 * That rule used to be stated three times — the cancellation arm, the failure arm, and the strip's
 * own success path — which is what made it a rule three places could drift out of
 * (docs/notes/audit-2026-08-06-quality.md, CPX-12). As a `finally` it is also stated for the two
 * exits the three arms did not cover: a `Throwable` that is not an `Exception`, and a strip that
 * throws after the fetch succeeded.
 *
 * A part file that outlived a process death — the crash landed between the fetch and the strip —
 * is not appended to either: `FileDownloader` truncates it, because the fetch is flagged
 * un-resumable at the call site.
 *
 * Every other kind of file fetches straight into its target, so there is nothing to clean up and
 * [block] is handed the target itself.
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
 * Fails the file when the active storage root no longer resolves to the row's own path.
 *
 * The path was resolved against the active root by `DownloadQueue.reconcile` at the start of the
 * drain; resolving somewhere else now means the root moved underneath the item (an SD card ejected
 * or remounted mid-transfer). Writing there anyway would split one download across two volumes —
 * where neither the sweep, the delete cascade nor `usedBytes()` can see the half on the inactive
 * root (audit DL-07). [StorageUnavailableException] is transient on purpose: the next drain
 * re-reconciles every path against whichever root answers then. Top-level for detekt's
 * function-count ceiling on the queue.
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
 * Running totals across an item's files, so the row's percentage is the *item's* and not the
 * current file's.
 *
 * Without this a 2 GB film would jump to 100 % while its 40 KB poster finished, then back to 0 %.
 * Sizes start from the rows already on disk so a resumed item does not restart its percentage.
 *
 * @param estimatedTotal the size the enqueue step predicted for the whole item. It is used as a
 *   floor for as long as **any** file's real size is still unknown, which is the permanent state of
 *   a transcoded download: the server encodes on the fly and never sends a `Content-Length`, so
 *   without the floor `bytesTotal` would collapse onto `bytesDownloaded` and the queue tab would
 *   read 100 % from the first chunk (DECISIONS.md, 2026-07-29). Once every file has reported a real
 *   size — the ordinary end of an `ORIGINAL` download — the estimate is dropped and the exact sum
 *   wins, so an estimate that was too generous cannot leave a finished item short of 100 %.
 * @param seededProjection the projection already on the row when the transfer started — for an
 *   episode, what its finished siblings at the same quality suggest it will weigh. It holds the
 *   line until the scanner has read a cluster of its own, so the very first sample of a seeded
 *   download does not blank the figure the user was already shown.
 *
 * ### Two writers
 * An item is drained by two lanes (`DownloadQueue.transfer`), and both report into this one total —
 * that is the whole point of it. They never meet on the same entry, because a key is a file id and
 * a file belongs to exactly one lane, but they do write while the other is summing: hence the
 * concurrent maps, which cost a lock-free `put` per 64 KB callback and rule out the
 * `ConcurrentModificationException` a plain `LinkedHashMap` would eventually raise in the middle of
 * a two-hour download. The sums are weakly consistent by construction, which is what a progress
 * figure asks for: no sample can be *lost*, only briefly overtaken.
 */
private class ItemProgress(
    files: List<DownloadFileEntity>,
    private val estimatedTotal: Long,
    seededProjection: Long? = null,
) {
    private val downloaded = ConcurrentHashMap(files.associate { it.id to it.bytesDownloaded })
    private val totals = ConcurrentHashMap(files.associate { it.id to it.bytesTotal })

    /**
     * The media file's projected finished size, or `null` for "no better answer than the ceiling".
     *
     * Written by the queue on the throttle's cadence and cleared when the media file completes.
     * Deliberately *not* folded into [bytesTotal]: the ceiling is a promise the enqueue step made
     * and the projection is evidence arriving afterwards, and conflating them would let a mid-flight
     * guess overwrite the only deterministic number on the row.
     *
     * `@Volatile` because the ordinary lane writes it and the sidecar lane reads it through
     * [projectedBytes] on every publication of its own.
     */
    @Volatile
    var mediaProjection: Long? = seededProjection

    /**
     * What the whole item is projected to weigh, or `null` when nothing is projecting it.
     *
     * The media file's own entry in [totals] is still `0` while its length is unknown, so that sum
     * is exactly the item's *other* files — artwork and subtitles, a rounding error next to the
     * video, added for completeness rather than for accuracy. Clamped into
     * `[bytesDownloaded, bytesTotal]` so a projection can never claim less than what has landed nor
     * more than the ceiling.
     */
    val projectedBytes: Long?
        get() = mediaProjection?.let { (it + totals.values.sum()).coerceIn(bytesDownloaded, bytesTotal) }

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

    val bytesTotal: Long
        get() {
            val known = totals.values.sum()
            val floor = if (totals.values.any { it <= 0L }) estimatedTotal else 0L
            return maxOf(known, floor, bytesDownloaded)
        }
}
