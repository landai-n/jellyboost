package dev.jellyboost.data.downloads.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.datastore.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Asks WorkManager to run the download queue.
 *
 * Behind an interface so the enqueue/pause/resume paths can be unit-tested without WorkManager,
 * exactly like `UserDataSyncScheduler` in `:data`.
 */
interface DownloadScheduler {
    /**
     * Makes sure the queue is running, without disturbing a run already in progress.
     *
     * This is `ExistingWorkPolicy.KEEP` — the plan's own choice. The worker drains whatever is
     * pending *when it runs*, so enqueueing five items in a row must not keep replacing (and
     * therefore restarting) the one job that is already downloading the first of them.
     */
    suspend fun ensureRunning()

    /**
     * Restarts the queue with freshly-read constraints.
     *
     * Used when the constraints themselves changed — the user flipped Wi-Fi-only, or resumed a
     * paused item — because a running job keeps the constraints it was enqueued with.
     */
    suspend fun restart()

    /**
     * Stops the running job **and waits for it to be gone**. Partial files stay on disk, so the
     * next run resumes them.
     *
     * The waiting is the contract, not an implementation detail. Callers stop the queue before
     * unlinking files precisely so the downloader cannot be holding a handle to one of them, and
     * WorkManager's cancellation is asynchronous: a fire-and-forget cancel returns while the
     * transfer is still writing, and `FileDownloader` re-creates the item directory for every file
     * it opens — so the cascade's delete raced a `mkdirs()` that put it straight back
     * (docs/notes/audit-2026-07.md, STAB-04).
     */
    suspend fun stop()
}

/** [DownloadScheduler] on WorkManager, per docs/PLAN.md's "Download pipeline" → Enqueue. */
@Singleton
class WorkManagerDownloadScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: AppPreferences,
    ) : DownloadScheduler {
        override suspend fun ensureRunning() = enqueue(ExistingWorkPolicy.KEEP)

        override suspend fun restart() = enqueue(ExistingWorkPolicy.REPLACE)

        override suspend fun stop() {
            try {
                val manager = WorkManager.getInstance(context)
                // Awaiting the Operation only means WorkManager has *recorded* the cancellation;
                // the worker's coroutine unwinds afterwards, which is the part a caller about to
                // unlink files actually cares about.
                manager.cancelUniqueWork(UNIQUE_WORK_NAME).await()
                awaitNotRunning(manager)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.w(error, "Could not stop the download queue")
            }
        }

        /**
         * Waits for the unique work to leave `RUNNING`, and gives up after [STOP_TIMEOUT].
         *
         * The ceiling matters more than the wait: the worker stops at its next `ensureActive()`,
         * which inside a 64 KB copy loop is milliseconds, but a socket wedged mid-read could hold
         * it indefinitely — and blocking a *delete* on that would trade a leaked directory for a
         * frozen UI. The orphan sweep on the next drain is what covers the give-up case.
         */
        private suspend fun awaitNotRunning(manager: WorkManager) {
            val stopped =
                withTimeoutOrNull(STOP_TIMEOUT) {
                    manager
                        .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                        .first { infos -> infos.none { it.state == WorkInfo.State.RUNNING } }
                }
            if (stopped == null) {
                Timber.w("The download queue was still running %s after being cancelled", STOP_TIMEOUT)
            }
        }

        private suspend fun enqueue(policy: ExistingWorkPolicy) {
            val wifiOnly = preferences.downloadOverWifiOnly.first()

            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            // The Wi-Fi-only preference *is* this line. Handing the rule to
                            // WorkManager rather than checking it ourselves is what makes leaving
                            // Wi-Fi mid-transfer suspend the job (and rejoining resume it) with no
                            // code of ours involved.
                            .setRequiredNetworkType(
                                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                            ).setRequiresStorageNotLow(true)
                            .build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
            }.onFailure {
                // Losing the scheduled run must not fail the enqueue: the rows are already in Room
                // and the next `ensureRunning()` picks them up.
                Timber.w(it, "Could not enqueue the download queue")
            }
        }

        companion object {
            /** docs/PLAN.md: `enqueueUniqueWork("downloads", KEEP)`. */
            const val UNIQUE_WORK_NAME = "downloads"

            /**
             * WorkManager's own minimum backoff; anything smaller is clamped to it anyway.
             *
             * It is also the retry cadence `DownloadQueue.MAX_ATTEMPTS` is sized against: a
             * transient failure comes back here as `Result.retry()`, so the attempts are spread
             * 30 s, 60 s, 120 s, 240 s apart rather than hammering a server that is restarting.
             */
            const val BACKOFF_SECONDS = 30L

            /** How long [stop] waits for a cancelled worker to actually stop writing. */
            val STOP_TIMEOUT: Duration = 5.seconds
        }
    }
