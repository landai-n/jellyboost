package dev.jellyfinnative.data.userdata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jellyfinnative.core.network.session.SessionGate
import kotlinx.coroutines.CancellationException
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
 * The worker itself is deliberately thin: the session check lives in [SessionGate] and the decision
 * matrix lives in [UserDataSyncer], both of which run on the JVM, while a `CoroutineWorker` only runs
 * on a device.
 *
 * ### Enqueued from three places
 * - a local write whose push failed ([UserDataRepositoryImpl]);
 * - app start, when rows are already pending ([UserDataSyncTrigger]);
 * - every transition back to `ConnectionState.ONLINE` (the same trigger).
 *
 * Any of the three can run before `MainViewModel` has restored the session — app start is the
 * obvious one, but a reconnect right after process death races it too — so [doWork] consults
 * [SessionGate] before touching [UserDataSyncer], the same fix M7 shipped for `DownloadWorker`
 * (see `DECISIONS.md`, "the download worker restores the session itself").
 */
@HiltWorker
class UserDataSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val sessionGate: SessionGate,
        private val syncer: UserDataSyncer,
    ) : CoroutineWorker(appContext, workerParameters) {
        /**
         * A failed drain is [Result.retry], never [Result.failure]: the rows are still pending, so
         * WorkManager's backoff is exactly the behaviour wanted, and a permanent failure would
         * silently strand the user's watch state on the device.
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
                // A cancelled worker is not a failed one — WorkManager stopped it, and reporting
                // that as an unexpected failure would ask for a retry of work nobody asked to run.
                throw cancellation
            } catch (error: Exception) {
                // Room or the SDK throwing something outside the mapped taxonomy must not kill the
                // work chain — the pending rows are untouched and the next attempt sees them again.
                Timber.e(error, "User-data sync failed unexpectedly")
                Result.retry()
            }
        }

        companion object {
            /** Unique-work name; one drain at a time, app-wide. */
            const val UNIQUE_WORK_NAME = "user-data-sync"
        }
    }
