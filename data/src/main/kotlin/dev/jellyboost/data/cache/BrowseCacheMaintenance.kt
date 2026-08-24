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
 * Eviction half of the browse cache's retention policy.
 *
 * Downloads are excluded by **source**, never by age: deleting an [ItemSource.DOWNLOAD] row would
 * orphan its files on disk and blank the offline detail page — including the series and season rows
 * `DownloadEnqueuer` writes behind a downloaded episode. A `BROWSE_CACHE` row is disposable; losing
 * one costs one network read.
 *
 * Two bounds, because age alone is not one: rows are written by *browsing*, so an afternoon of
 * scrolling can add tens of thousands the TTL will not touch for a month.
 *
 * Triggered at startup and every [WRITES_BETWEEN_SWEEPS]th write-through, not by a periodic worker:
 * the table is only grown by using the app, so writes are the honest clock.
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

        fun start() {
            startOnce { scope.launch { sweep() } }
        }

        /**
         * The sweep goes to the application scope: a write that succeeded must not wait on eviction.
         * [sweeping] keeps a slow sweep from being restarted by the writes that land during it.
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
         * Age first, then the cap: the TTL is the bound with a *reason*, so running it first leaves
         * the cap only rows still within their lifetime, where "oldest" is a genuine tie-break.
         * Public so tests can await it instead of racing the scope.
         */
        suspend fun sweep(): Int = evictExpired() + trimToCap()

        /** @return rows dropped — also `0` when the sweep failed, which is never an error. */
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

        /** @return rows dropped — also `0` when the trim failed, which is never an error. */
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
             * A month: covers a holiday's worth of offline use (a dropped row is a detail page that
             * no longer opens) without keeping rows the server may have deleted since. Being wrong
             * either way costs one network read.
             */
            val BROWSE_CACHE_TTL: Duration = Duration.ofDays(30)

            /**
             * A page is fifty items, so 5000 rows is two hundred pages — reached only by the
             * pathological session the TTL is blind to. Each row carries a multi-kilobyte
             * `BaseItemDto` blob, so this is a cache measured in tens of megabytes; rows are simply
             * what the database can bound cheaply (an indexed `OFFSET`).
             */
            const val BROWSE_CACHE_MAX_ROWS = 5_000

            /**
             * One write is one server response (up to fifty items), so 25 is a sweep every few
             * hundred cached rows at the outside — a real ceiling within a session, rare enough that
             * the two `DELETE`s never show up next to the write path.
             */
            const val WRITES_BETWEEN_SWEEPS = 25
        }
    }
