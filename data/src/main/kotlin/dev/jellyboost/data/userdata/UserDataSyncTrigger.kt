package dev.jellyboost.data.userdata

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.StartOnce
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.connectivity.onEachOnlineStretch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains at the two moments a failed write cannot ask for itself: **app start** and **every return
 * to the network**. An offline write never enqueues anything and waits for this trigger; a process
 * killed with rows pending leaves nothing enqueued for WorkManager to persist; and
 * `NetworkType.CONNECTED` only revives a job that is still pending, not one that exhausted its
 * retries. [onEachOnlineStretch] covers both with one path — and documents why its initial value is
 * deliberately not dropped.
 *
 * The count query guards the enqueue so a normal launch costs one indexed `COUNT(*)` and no work.
 */
@Singleton
class UserDataSyncTrigger
    @Inject
    internal constructor(
        private val connectionState: ConnectionStateProvider,
        private val userDataDao: UserDataDao,
        private val scheduler: UserDataSyncScheduler,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val startOnce = StartOnce()

        fun start() {
            startOnce {
                scope.launch { connectionState.onEachOnlineStretch { enqueueIfPending() } }
            }
        }

        suspend fun enqueueIfPending() {
            val pending =
                try {
                    withContext(ioDispatcher) { userDataDao.countPendingSync() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: SQLiteException) {
                    Timber.w(error, "Could not count pending user-data rows")
                    return
                }

            if (pending == 0) return

            Timber.i("%d user-data row(s) pending and the server is reachable; scheduling a sync", pending)
            scheduler.enqueue()
        }
    }
