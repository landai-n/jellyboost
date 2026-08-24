package dev.jellyboost.data.userdata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jellyboost.core.network.session.SessionGate
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Thin on purpose: the session check is [SessionGate]'s and the decision matrix is
 * [UserDataSyncer]'s, both JVM-testable, while a `CoroutineWorker` only runs on a device.
 *
 * Enqueued by a failed local push, by app start with rows pending, and by every transition back to
 * `ConnectionState.ONLINE`. Any of the three can run before `MainViewModel` restored the session — a
 * reconnect right after process death races it — hence the [SessionGate] check in [doWork].
 */
@HiltWorker
internal class UserDataSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val sessionGate: SessionGate,
        private val syncer: UserDataSyncer,
    ) : CoroutineWorker(appContext, workerParameters) {
        /**
         * A failed drain is [Result.retry], never [Result.failure]: the rows are still pending, and
         * a permanent failure would silently strand the user's watch state on the device.
         */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun doWork(): Result {
            if (!sessionGate.ensureSession()) {
                Timber.i("No session yet; the user-data sync will be retried")
                return Result.retry()
            }

            return try {
                when (syncer.sync()) {
                    SyncOutcome.NOTHING_PENDING, SyncOutcome.DRAINED -> Result.success()
                    SyncOutcome.RETRY -> Result.retry()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // An exception outside the mapped taxonomy must not kill the work chain; the pending
                // rows are untouched and the next attempt sees them again.
                Timber.e(error, "User-data sync failed unexpectedly")
                Result.retry()
            }
        }

        companion object {
            /** Unique-work name; one drain at a time, app-wide. */
            const val UNIQUE_WORK_NAME = "user-data-sync"
        }
    }
