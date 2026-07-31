package dev.jellyboost.data.userdata

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks for the pending user-data changes to be pushed once the device is back on a network.
 *
 * Behind an interface so the local-first write path can be unit-tested without WorkManager.
 */
interface UserDataSyncScheduler {
    /** Enqueues the sync as unique work; a second call while one is pending is a no-op. */
    fun enqueue()
}

/** [UserDataSyncScheduler] backed by WorkManager, per docs/PLAN.md's "Data layer". */
@Singleton
class WorkManagerUserDataSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : UserDataSyncScheduler {
        override fun enqueue() {
            val request =
                OneTimeWorkRequestBuilder<UserDataSyncWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build()

            // KEEP, not REPLACE: the worker drains whatever is pending when it runs, so a burst of
            // failed toggles must not keep pushing the one scheduled run further into the future.
            runCatching {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(UserDataSyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure {
                // Losing the scheduled retry must never break the local write that triggered it —
                // the row keeps `toBeSynced = true` and the next successful push clears it.
                Timber.w(it, "Could not enqueue the user-data sync worker")
            }
        }

        private companion object {
            /** WorkManager's own minimum backoff; anything smaller is silently clamped anyway. */
            const val BACKOFF_SECONDS = 30L
        }
    }
