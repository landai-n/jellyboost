package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.StartOnce
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
import java.util.concurrent.atomic.AtomicInteger
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
 * ### Two bounds, because age alone is not one
 * The TTL sweep answers "how long is a row worth keeping". It does **not** answer "how many", and
 * that was the gap: rows are written by *browsing*, which happens far faster than a month passes,
 * so a single afternoon of scrolling a large library could add tens of thousands of rows that the
 * age sweep would not touch for thirty days — each carrying a multi-kilobyte `BaseItemDto` blob
 * (audit 2026-08-08, PERF-17). [BROWSE_CACHE_MAX_ROWS] is the second bound: past it, the oldest
 * rows go however fresh they are.
 *
 * ### When it runs
 * Once at startup, from `JellyboostApplication.onCreate` on the application scope — the same place
 * and for the same reason as `UserDataSyncTrigger` and `DownloadedMetadataRefresher` — **and** every
 * [WRITES_BETWEEN_SWEEPS]th write-through, which is what closes the other half of the same gap: a
 * process that stays alive for a long browsing session used to have no eviction at all between its
 * one startup sweep and its death. The counter is the whole of the throttle: a sweep is two indexed
 * `DELETE`s, cheap but not free, and it must not ride the write path itself.
 *
 * A periodic worker would be the wrong shape for either trigger. The table is only ever grown *by
 * using the app*, so the writes are the honest clock — a scheduled job would run on a device nobody
 * has opened, and not run during the session that is actually filling the table.
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
        private val startOnce = StartOnce()
        private val writesSinceSweep = AtomicInteger(0)
        private val sweeping = AtomicBoolean(false)

        /** Sweeps the browse cache, once. Idempotent — see [StartOnce]. */
        fun start() {
            startOnce { scope.launch { sweep() } }
        }

        /**
         * Counts one write-through, and sweeps when enough of them have piled up.
         *
         * Called by `BrowseCacheWriter` after each successful item write. Cheap by construction:
         * an atomic increment on all but every [WRITES_BETWEEN_SWEEPS]th call, and the sweep itself
         * is handed to the application scope rather than run on the writer's coroutine — an
         * eviction pass must never be something the user waits for, and a write that succeeded is
         * not made better or worse by it.
         *
         * [sweeping] keeps a slow sweep from being started again by the writes that land during it.
         */
        fun onWriteThrough() {
            if (writesSinceSweep.incrementAndGet() < WRITES_BETWEEN_SWEEPS) return
            writesSinceSweep.set(0)
            if (!sweeping.compareAndSet(false, true)) return

            scope.launch {
                try {
                    sweep()
                } finally {
                    sweeping.set(false)
                }
            }
        }

        /**
         * Both bounds, in order: drop what is too old, then drop what is beyond the row cap.
         *
         * Age first, because it is the bound with a *reason* — a row past the TTL is near-certainly
         * stale — and running it first means the cap only ever has to remove rows that are still
         * within their lifetime, which is the case where "oldest" is a genuine tie-break rather
         * than a rediscovery of what the TTL already knew.
         *
         * Suspending and public so tests can await it directly instead of racing the scope.
         *
         * @return how many rows were dropped in total, or `0` if the sweep could not run.
         */
        suspend fun sweep(): Int = evictExpired() + trimToCap()

        /**
         * Drops every browse-cache row last written more than [BROWSE_CACHE_TTL] ago.
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

        /**
         * Drops the browse-cache rows beyond the newest [BROWSE_CACHE_MAX_ROWS].
         *
         * @return how many rows were dropped, or `0` if the trim could not run.
         */
        suspend fun trimToCap(): Int {
            val dropped =
                try {
                    withContext(ioDispatcher) {
                        itemDao.trimBrowseCacheTo(BROWSE_CACHE_MAX_ROWS, ItemSource.BROWSE_CACHE)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: SQLiteException) {
                    Timber.w(error, "Could not trim the browse cache to %d rows", BROWSE_CACHE_MAX_ROWS)
                    return 0
                }

            if (dropped > 0) {
                Timber.i("Trimmed %d browse-cache row(s) beyond the %d newest", dropped, BROWSE_CACHE_MAX_ROWS)
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

            /**
             * How many browse-cache rows are kept at all, whatever their age.
             *
             * The bound the TTL cannot supply, and chosen against the same question: how much
             * browsing is worth being able to open offline? A page is fifty items, so this is two
             * hundred pages — far more than anyone scrolls through and then wants back without a
             * network, and enough that the cap is only ever reached by the pathological session
             * (paging a ten-thousand-item library end to end) the TTL was blind to.
             *
             * The unit that matters is bytes, not rows: each row carries a multi-kilobyte
             * `BaseItemDto` blob, so five thousand of them is a cache measured in tens of
             * megabytes. Rows are what the database can bound cheaply — an indexed `OFFSET` — and
             * blob sizes vary by less than the order of magnitude this number is chosen at.
             *
             * Being wrong in either direction costs one network read per lost row, exactly as with
             * the TTL, which is what makes both bounds safe to set by reasoning rather than by
             * measurement.
             */
            const val BROWSE_CACHE_MAX_ROWS = 5_000

            /**
             * How many write-throughs go by between sweeps.
             *
             * One write is one server response — a page of up to fifty items, or a single item's
             * detail read — so this is a sweep every few hundred cached rows at the outside, and
             * every twenty-five taps at the very least. Frequent enough that the cap is a real
             * ceiling within a session, rare enough that the two `DELETE`s never show up next to
             * the write path they are counted from.
             */
            const val WRITES_BETWEEN_SWEEPS = 25
        }
    }
