package dev.jellyfinnative.data.userdata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jellyfinnative.core.database.dao.UserDataDao
import timber.log.Timber

/**
 * Pushes the rows [UserDataRepository] could not deliver (`toBeSynced = true`) to the server.
 *
 * **Stubbed at M4 by design.** The milestone list says "M4 Item detail + user data (local-first
 * writes + EventBus; **sync worker stubbed**)" — the real drain, including most-recent-wins
 * conflict resolution, belongs to M8 (docs/PLAN.md, "Milestones"). Everything around the worker is
 * real: the constraints, the unique-work policy, the backoff and the pending-row query all work
 * today, so M8 only has to fill in [doWork].
 */
@HiltWorker
class UserDataSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParameters: WorkerParameters,
        private val userDataDao: UserDataDao,
    ) : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result {
            val pending = userDataDao.countPendingSync()

            // M8: most-recent-wins sync — for each pending row, fetch the server's userData,
            // compare its lastPlayedDate against the row's updatedAt, push only when the local
            // copy is newer, otherwise adopt the server value; clear `toBeSynced` either way.
            Timber.i("User-data sync worker: %d row(s) pending; real sync arrives in M8", pending)

            return Result.success()
        }

        companion object {
            /** Unique-work name; one drain at a time, app-wide. */
            const val UNIQUE_WORK_NAME = "user-data-sync"
        }
    }
