package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The **read** half of the browse cache's retention policy: it throws rows away again.
 *
 * `BrowseCacheWriter` mirrors every successful server read into the `items` table, and until this
 * class existed nothing ever deleted one — the table grew monotonically for the life of the install
 * and an item deleted on the server kept resolving offline forever (audit 2026-08-06, HYG-1). The
 * plan's policy was only ever half-implemented: docs/PLAN.md, "Data layer" gives `ItemEntity` both a
 * `cachedAt` column and the rule that "DOWNLOAD rows never evicted", which is a statement about an
 * eviction pass that had never been wired up.
 *
 * ### What it may delete, and what it may not
 * Exactly one query, [ItemDao.evictBrowseCacheOlderThan], and it excludes downloads by **source**
 * rather than by age: an [ItemSource.DOWNLOAD] row is never evicted, however stale it looks, because
 * deleting it would orphan the files on disk with no row pointing at them, and because it is what
 * the offline detail page reads. That includes the series and season rows behind a downloaded
 * episode, which `DownloadEnqueuer` also writes as `DOWNLOAD`. A `BROWSE_CACHE` row, by contrast, is
 * disposable by definition: losing one costs a network read the next time the user browses past it,
 * and nothing at all while they are online.
 *
 * ### When it runs
 * Once per process, from `JellyboostApplication.onCreate`, on the application scope — the same place
 * and for the same reason as `UserDataSyncTrigger` and `DownloadedMetadataRefresher`. A sweep is a
 * single indexed `DELETE`; putting it on a schedule (a periodic worker) would buy a device that is
 * never restarted a slightly smaller table, at the cost of a scheduled job, and the table is only
 * ever grown *by using the app*, which is also what restarts it.
 */
@Singleton
class BrowseCacheMaintenance
    @Inject
    constructor(
        private val itemDao: ItemDao,
        private val clock: Clock,
        @ApplicationScope private val scope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val started = AtomicBoolean(false)

        /**
         * Sweeps the expired browse-cache rows, once.
         *
         * Idempotent — calling it twice does not sweep twice, which matters because `:app` calls it
         * from `Application.onCreate` and a process can be re-created without the singleton being.
         */
        fun start() {
            if (!started.compareAndSet(false, true)) return

            scope.launch { evictExpired() }
        }

        /**
         * Drops every browse-cache row last written more than [BROWSE_CACHE_TTL] ago.
         *
         * Suspending and public so tests can await it directly instead of racing the scope.
         *
         * @return how many rows were dropped, or `0` if the sweep could not run.
         */
        suspend fun evictExpired(): Int {
            val cutoff = clock.instant().minus(BROWSE_CACHE_TTL)

            val dropped =
                try {
                    withContext(ioDispatcher) {
                        itemDao.evictBrowseCacheOlderThan(cutoff, ItemSource.BROWSE_CACHE)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: SQLiteException) {
                    Timber.w(error, "Could not evict expired browse-cache rows")
                    return 0
                }

            if (dropped > 0) {
                Timber.i("Evicted %d browse-cache row(s) last written before %s", dropped, cutoff)
            }
            return dropped
        }

        companion object {
            /**
             * How long an unvisited browse-cache row is kept.
             *
             * docs/PLAN.md fixes the *policy* (`cachedAt` + "DOWNLOAD rows never evicted") but not a
             * number, so this is the project's, and it is chosen for what the row is actually for:
             * a `BROWSE_CACHE` row exists so that a **cached parent of a download** — a series page,
             * a season — still opens with no network, and so that a recently-browsed item does. Both
             * are about the recent past. A month covers a holiday's worth of offline use, which is
             * the longest stretch anyone plausibly spends away from their server with the app
             * already loaded, while still bounding a table whose rows each carry a multi-kilobyte
             * `BaseItemDto` blob.
             *
             * It is deliberately not shorter: eviction is not free to the user — a row dropped while
             * offline is a detail page that no longer opens. And not longer: past a month the row is
             * near-certainly stale (the server may have deleted the item outright, and nothing tells
             * this device so), and browsing past it again rewrites it at full fidelity anyway, which
             * makes the cost of being wrong one network read.
             */
            val BROWSE_CACHE_TTL: Duration = Duration.ofDays(30)
        }
    }
