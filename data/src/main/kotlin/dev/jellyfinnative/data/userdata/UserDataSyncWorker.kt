package dev.jellyfinnative.data.userdata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Pushes the rows [UserDataRepository] could not deliver (`toBeSynced = true`) to the server, and
 * adopts the server's copy where that one is newer (docs/PLAN.md, "Data layer" → most-recent-wins).
 *
 * Real since M8; it was deliberately a stub through M4–M7 ("M4 Item detail + user data … sync worker
 * stubbed"). Everything around the worker was already real — the `NetworkType.CONNECTED` constraint,
 * the unique-work policy, the exponential backoff, the pending-row query — so filling it in meant
 * writing [UserDataSyncer] and nothing else.
 *
 * The worker itself is deliberately three lines of mapping: the decision matrix lives in
 * [UserDataSyncer], which runs on the JVM, while a `CoroutineWorker` only runs on a device.
 *
 * ### Enqueued from three places
 * - a local write whose push failed ([UserDataRepositoryImpl]);
 * - app start, when rows are already pending ([UserDataSyncTrigger]);
 * - every transition back to `ConnectionState.ONLINE` (the same trigger).
 */
@HiltWorker
class UserDataSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val syncer: UserDataSyncer,
    ) : CoroutineWorker(appContext, workerParameters) {
        /**
         * A failed drain is [Result.retry], never [Result.failure]: the rows are still pending, so
         * WorkManager's backoff is exactly the behaviour wanted, and a permanent failure would
         * silently strand the user's watch state on the device.
         */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun doWork(): Result =
            try {
                when (syncer.sync()) {
                    SyncOutcome.NOTHING_PENDING, SyncOutcome.DRAINED -> Result.success()
                    SyncOutcome.RETRY -> Result.retry()
                }
            } catch (error: Exception) {
                // Room or the SDK throwing something outside the mapped taxonomy must not kill the
                // work chain — the pending rows are untouched and the next attempt sees them again.
                Timber.e(error, "User-data sync failed unexpectedly")
                Result.retry()
            }

        companion object {
            /** Unique-work name; one drain at a time, app-wide. */
            const val UNIQUE_WORK_NAME = "user-data-sync"
        }
    }
