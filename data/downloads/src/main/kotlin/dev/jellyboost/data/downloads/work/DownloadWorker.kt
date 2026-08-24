package dev.jellyboost.data.downloads.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.data.downloads.engine.DownloadQueue
import dev.jellyboost.data.downloads.engine.DownloadQueueListener
import dev.jellyboost.data.downloads.engine.DrainOutcome
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs [DownloadQueue] as foreground work: WorkManager decides *when* it may run, the queue decides
 * *what* runs, and this keeps a foreground notification current so Android lets the transfer take as
 * long as it takes.
 *
 * A *permanent* failure inside the queue is not a worker failure — the item is already
 * `DownloadStatus.ERROR` in Room, and retrying the whole job on a permanently broken item would loop
 * forever. A failure of the *machinery* is retried, and so is a transient one; see [toWorkerResult].
 *
 * [DrainOutcome.NO_SESSION] is retried too: nothing was attempted, so nothing is reported, the rows
 * stay `QUEUED`, and the next attempt after a sign-in picks the queue up exactly where it was.
 */
@HiltWorker
internal class DownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val queue: DownloadQueue,
        private val notifier: DownloadNotifier,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result {
            notifier.ensureChannel()
            // Before "Preparing…", not after: the notifier is a singleton and remembers what the
            // *previous* run last posted, so without this the first sample of this run could match
            // it and be skipped, leaving the notification on "Preparing…".
            notifier.resetPostedProgress()
            promote { notifier.startingForegroundInfo() }

            return try {
                queue.drain(listener).toWorkerResult()
            } catch (cancellation: CancellationException) {
                // Every Pause cancels this worker, and a cancelled worker is not a failed one:
                // WorkManager already decides what happens next.
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.e(error, "Download queue stopped unexpectedly")
                Result.retry()
            }
        }

        private val listener =
            object : DownloadQueueListener {
                override suspend fun onProgress(
                    download: DownloadEntity,
                    bytesDownloaded: Long,
                    bytesTotal: Long,
                ) {
                    // `null` means nothing the user would see changed since the last post.
                    val info =
                        notifier.foregroundInfoIfChanged(
                            itemId = download.itemId,
                            title = download.notificationTitle(),
                            bytesDownloaded = bytesDownloaded,
                            bytesTotal = bytesTotal,
                        ) ?: return
                    promote { info }
                }

                // The queue ran dry with this worker still alive: whatever it posts next describes
                // a new run of the drain, and must not be suppressed as "unchanged".
                override suspend fun onIdle() = notifier.resetPostedProgress()
            }

        /**
         * Posts a foreground update, swallowing the refusal: `setForeground` throws when the process is
         * not allowed to start a foreground service — a short window on some OEM builds, and after the
         * user revokes the notification permission — and losing the download over that is not
         * survivable. A cancellation is not a refusal: swallowing the one every Pause produces would
         * let the worker carry on inside a cancelled coroutine.
         */
        private suspend fun promote(info: () -> androidx.work.ForegroundInfo) {
            try {
                setForeground(info())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "Could not show the download notification")
            }
        }
    }

/**
 * How a drain outcome reaches WorkManager. `Result.retry()` re-runs the job on the `EXPONENTIAL`/30 s
 * backoff, deliberately the only retry mechanism in the pipeline: [DrainOutcome.RETRY] means the queue
 * kept the row `QUEUED` and counted the attempt, so WorkManager owns *when* the next try happens and
 * `DownloadQueue.MAX_ATTEMPTS` owns *whether* there is one.
 *
 * [DrainOutcome.INCOMPLETE] is a success on purpose: the item is already `ERROR` in Room, and
 * re-running the job over a permanently broken item would loop forever.
 */
internal fun DrainOutcome.toWorkerResult(): ListenableWorker.Result =
    when (this) {
        DrainOutcome.COMPLETED -> ListenableWorker.Result.success()

        DrainOutcome.INCOMPLETE -> {
            Timber.w("Download queue drained with at least one failed item")
            ListenableWorker.Result.success()
        }

        DrainOutcome.RETRY -> {
            Timber.i("The download queue stopped on a transient failure; WorkManager will re-run it")
            ListenableWorker.Result.retry()
        }

        DrainOutcome.NO_SESSION -> {
            Timber.i("No session yet; the download queue will be retried")
            ListenableWorker.Result.retry()
        }
    }

/** `Westworld — S01E02 · Chestnut` for an episode, the plain title otherwise. */
private fun DownloadEntity.notificationTitle(): String =
    seriesName?.takeIf { it.isNotBlank() }?.let { "$it — $itemName" } ?: itemName
