package dev.jellyfinnative.data.downloads.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyfinnative.core.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Stops the running job. Partial files stay on disk, so the next run resumes them. */
    fun stop()
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

        override fun stop() {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME) }
                .onFailure { Timber.w(it, "Could not stop the download queue") }
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

            /** WorkManager's own minimum backoff; anything smaller is clamped to it anyway. */
            const val BACKOFF_SECONDS = 30L
        }
    }
