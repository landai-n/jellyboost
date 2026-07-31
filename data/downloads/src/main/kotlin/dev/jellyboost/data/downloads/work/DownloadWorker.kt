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
 * Runs [DownloadQueue] as foreground work.
 *
 * The worker owns almost nothing: WorkManager decides *when* it may run (the Wi-Fi-only and
 * storage constraints `WorkManagerDownloadScheduler` attaches), the queue decides *what* runs, and
 * this class is the bit in between that keeps a foreground notification current so Android lets the
 * transfer take as long as it takes.
 *
 * A *permanent* failure inside the queue is not a worker failure: the item is already marked
 * `DownloadStatus.ERROR` in Room and shown as such in the Queue tab, and retrying the whole job on
 * a permanently broken item (deleted on the server, unreadable file) would loop forever. A failure
 * of the *machinery* — storage vanishing, a Room error — is retried, and so is a **transient**
 * one: see [toWorkerResult].
 *
 * The one other retry is [DrainOutcome.NO_SESSION]: on a cold start WorkManager can run this before
 * anything has restored the session, and `SessionGate` could not restore one either (the
 * user is signed out, or the credential store was unreadable). Nothing was attempted, so nothing is
 * reported; WorkManager's exponential backoff re-runs the job, and the next attempt after a sign-in
 * picks the queue up exactly where it was. The rows stay `QUEUED` — "Waiting" in the Queue tab —
 * throughout.
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
            promote { notifier.startingForegroundInfo() }

            return try {
                queue.drain(listener).toWorkerResult()
            } catch (cancellation: CancellationException) {
                // Every Pause cancels this worker, and a cancelled worker is not a failed one:
                // WorkManager already decides what happens next. Logging it at ERROR and asking for
                // a retry misreported the app's most ordinary download action as machinery failure.
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
                    // `null` means nothing the user would see changed since the last post — the
                    // throttle calls this at up to six times a second, and the whole percent it
                    // renders moves far less often than that (docs/notes/audit-2026-07.md, PERF-12).
                    val info =
                        notifier.foregroundInfoIfChanged(
                            itemId = download.itemId,
                            title = download.notificationTitle(),
                            bytesDownloaded = bytesDownloaded,
                            bytesTotal = bytesTotal,
                        ) ?: return
                    promote { info }
                }

                override suspend fun onIdle() = Unit
            }

        /**
         * Posts a foreground update, swallowing the refusal.
         *
         * `setForeground` throws when the process is not allowed to start a foreground service —
         * a short window on some OEM builds, and after the user revokes the notification
         * permission. Losing the *notification* is survivable; losing the download because of it is
         * not.
         *
         * A cancellation is not a refusal: `runCatching` used to swallow the one every Pause
         * produces here too, turning a stop into "could not show the notification" and letting the
         * worker carry on inside a cancelled coroutine.
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
 * How a drain outcome reaches WorkManager.
 *
 * A top-level function so the ladder can be exercised on the JVM: constructing a `CoroutineWorker`
 * needs a `Context` and a live `WorkerParameters`, and the decision worth pinning is which outcomes
 * come back for another run.
 *
 * `Result.retry()` re-runs the job on the `EXPONENTIAL`/30 s backoff
 * `WorkManagerDownloadScheduler` attaches, which is deliberately the only retry mechanism in the
 * pipeline: [DrainOutcome.RETRY] means the queue kept the row `QUEUED` and counted the attempt, so
 * WorkManager owns *when* the next try happens and `DownloadQueue.MAX_ATTEMPTS` owns *whether*
 * there is one (docs/notes/audit-2026-07.md, STAB-01).
 *
 * [DrainOutcome.INCOMPLETE] is a success on purpose: the item is already `ERROR` in Room and shown
 * as such, and re-running the job over a permanently broken item would loop forever.
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
