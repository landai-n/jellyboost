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
 * Asks for a user-data drain at the two moments a failed write cannot ask for itself: **app start**
 * and **every return to the network**.
 *
 * `UserDataRepositoryImpl` already enqueues the worker when an *online* push fails. It does
 * **not** attempt a push (or enqueue) at all while offline — an offline write only sets
 * `toBeSynced` and waits for this trigger. Neither path covers the two cases that actually matter:
 *
 * - the app was killed (or the device rebooted) while rows were still pending — nothing is left to
 *   enqueue anything, and WorkManager only persists work that was enqueued in the first place;
 * - connectivity came back. `NetworkType.CONNECTED` gets a *pending* job running again, but a run
 *   that already failed and exhausted its retries, or one that was never enqueued, needs a nudge.
 *
 * [onEachOnlineStretch] is what covers both with one code path, and carries the reasoning for why
 * the initial value is deliberately not dropped here.
 *
 * A count query guards the enqueue so a normal launch — the overwhelmingly common case, with
 * nothing pending — costs one indexed `COUNT(*)` on `toBeSynced` and schedules no work at all.
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

        /** Begins watching the connection. Idempotent — see [StartOnce]. */
        fun start() {
            startOnce {
                scope.launch { connectionState.onEachOnlineStretch { enqueueIfPending() } }
            }
        }

        /** Enqueues the drain, but only when there is something to drain. */
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
