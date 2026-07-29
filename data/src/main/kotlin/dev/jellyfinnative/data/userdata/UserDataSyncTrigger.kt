package dev.jellyfinnative.data.userdata

import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.di.ApplicationScope
import dev.jellyfinnative.core.network.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks for a user-data drain at the two moments a failed write cannot ask for itself: **app start**
 * and **every return to the network**.
 *
 * `UserDataRepositoryImpl` already enqueues the worker when an *online* push fails. Since M9 it does
 * **not** attempt a push (or enqueue) at all while offline (DECISIONS.md, 2026-07-29) — an offline
 * write only sets `toBeSynced` and waits for this trigger. Neither path covers the two cases that
 * actually deliver the milestone's definition of done:
 *
 * - the app was killed (or the device rebooted) while rows were still pending — nothing is left to
 *   enqueue anything, and WorkManager only persists work that was enqueued in the first place;
 * - connectivity came back. `NetworkType.CONNECTED` gets a *pending* job running again, but a run
 *   that already failed and exhausted its retries, or one that was never enqueued, needs a nudge.
 *
 * The state flow starts at its current value, so the first collection is the app-start check and
 * every later `false → true` edge is the reconnect one; one code path covers both.
 *
 * A count query guards the enqueue so a normal launch — the overwhelmingly common case, with
 * nothing pending — costs one indexed `COUNT(*)` on `toBeSynced` and schedules no work at all.
 */
@Singleton
class UserDataSyncTrigger
    @Inject
    constructor(
        private val connectionState: ConnectionStateProvider,
        private val userDataDao: UserDataDao,
        private val scheduler: UserDataSyncScheduler,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val started = AtomicBoolean(false)

        /**
         * Begins watching the connection. Idempotent — calling it twice does not double the watch,
         * which matters because `:app` calls it from `Application.onCreate` and a process can be
         * re-created without the singleton being.
         */
        fun start() {
            if (!started.compareAndSet(false, true)) return

            scope.launch {
                connectionState.state
                    .map { it.isOnline }
                    .distinctUntilChanged()
                    .collect { isOnline -> if (isOnline) enqueueIfPending() }
            }
        }

        /** Enqueues the drain, but only when there is something to drain. */
        suspend fun enqueueIfPending() {
            @Suppress("TooGenericExceptionCaught")
            val pending =
                try {
                    withContext(ioDispatcher) { userDataDao.countPendingSync() }
                } catch (error: Exception) {
                    Timber.w(error, "Could not count pending user-data rows")
                    return
                }

            if (pending == 0) return

            Timber.i("%d user-data row(s) pending and the server is reachable; scheduling a sync", pending)
            scheduler.enqueue()
        }
    }
