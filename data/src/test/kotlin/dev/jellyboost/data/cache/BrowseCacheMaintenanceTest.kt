package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.data.cache.CacheFixtures.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [BrowseCacheMaintenance] — the eviction pass that reclaims browse-cache rows.
 *
 * The one rule worth more than the rest: **a download is never swept.** Getting that wrong deletes
 * the row an offline detail page is rebuilt from and orphans the files it points at, which is the
 * failure the whole `ItemSource` distinction exists to make impossible. It is pinned here as the
 * *arguments the sweep passes*, because that is where it is decided — the SQL predicate itself is
 * Room's side of the contract and is exercised on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCacheMaintenanceTest {
    private val itemDao = mockk<ItemDao>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private val cutoff = slot<Instant>()
    private val source = slot<ItemSource>()
    private val keep = slot<Int>()
    private val trimSource = slot<ItemSource>()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.evictBrowseCacheOlderThan(capture(cutoff), capture(source)) } returns 0
        coEvery { itemDao.trimBrowseCacheTo(capture(keep), capture(trimSource)) } returns 0
    }

    private fun TestScope.maintenance() =
        BrowseCacheMaintenance(
            itemDao = itemDao,
            clock = clock,
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    @DisplayName("the sweep only ever targets browse-cache rows, never downloads")
    fun sweepsBrowseCacheOnly() =
        runTest {
            maintenance().evictExpired()

            // Downloads are excluded by source, not by age: a download is never evicted, however
            // stale it looks, because its files are on disk and this row is what points at them.
            source.captured shouldBe ItemSource.BROWSE_CACHE
        }

    @Test
    @DisplayName("rows are kept for the documented TTL, counted back from now")
    fun cutoffIsTheTtlAgo() =
        runTest {
            maintenance().evictExpired()

            cutoff.captured shouldBe NOW.minus(BrowseCacheMaintenance.BROWSE_CACHE_TTL)
        }

    @Test
    @DisplayName("the TTL is a month, not an accident")
    fun ttlIsThirtyDays() {
        // Pinned so that a future change to the constant is a decision someone makes on purpose:
        // shortening it means detail pages that stop opening offline sooner.
        BrowseCacheMaintenance.BROWSE_CACHE_TTL.toDays() shouldBe 30
    }

    @Test
    @DisplayName("the sweep reports how many rows it dropped")
    fun reportsTheCount() =
        runTest {
            coEvery { itemDao.evictBrowseCacheOlderThan(any(), any()) } returns 42

            maintenance().evictExpired() shouldBe 42
        }

    @Test
    @DisplayName("start sweeps once, however many times it is called")
    fun startIsIdempotent() =
        runTest {
            val maintenance = maintenance()

            maintenance.start()
            maintenance.start()
            advanceUntilIdle()

            coVerify(exactly = 1) { itemDao.evictBrowseCacheOlderThan(any(), any()) }
        }

    @Test
    @DisplayName("a failed sweep is a logged warning, never a crash on app start")
    fun swallowsStorageFailure() =
        runTest {
            coEvery { itemDao.evictBrowseCacheOlderThan(any(), any()) } throws
                SQLiteException("disk full")

            maintenance().evictExpired() shouldBe 0
        }

    @Test
    @DisplayName("a cancelled sweep propagates instead of being logged as a failure")
    fun cancellationPropagates() =
        runTest {
            coEvery { itemDao.evictBrowseCacheOlderThan(any(), any()) } throws
                CancellationException("scope cancelled")

            shouldThrow<CancellationException> { maintenance().evictExpired() }
        }

    // ---- the row cap ------------------------------------------------------------------------------

    @Test
    @DisplayName("the trim only ever targets browse-cache rows, never downloads")
    fun trimsBrowseCacheOnly() =
        runTest {
            maintenance().trimToCap()

            trimSource.captured shouldBe ItemSource.BROWSE_CACHE
            keep.captured shouldBe BrowseCacheMaintenance.BROWSE_CACHE_MAX_ROWS
        }

    @Test
    @DisplayName("a sweep bounds the table by age *and* by row count")
    fun sweepAppliesBothBounds() =
        runTest {
            // Age alone leaves within-session growth unbounded: rows are written by browsing, which
            // happens far faster than a month passes.
            coEvery { itemDao.evictBrowseCacheOlderThan(any(), any()) } returns 3
            coEvery { itemDao.trimBrowseCacheTo(any(), any()) } returns 4

            maintenance().sweep() shouldBe 7
        }

    @Test
    @DisplayName("a failed trim is a logged warning, never a crash")
    fun trimSwallowsStorageFailure() =
        runTest {
            coEvery { itemDao.trimBrowseCacheTo(any(), any()) } throws SQLiteException("disk full")

            maintenance().trimToCap() shouldBe 0
        }

    @Test
    @DisplayName("write-throughs below the threshold sweep nothing")
    fun writesBelowThresholdDoNotSweep() =
        runTest {
            val maintenance = maintenance()

            repeat(BrowseCacheMaintenance.WRITES_BETWEEN_SWEEPS - 1) { maintenance.onWriteThrough() }
            advanceUntilIdle()

            // The counter is the whole of the throttle: a sweep is two indexed DELETEs, cheap but
            // not free, and it must not ride the write path.
            coVerify(exactly = 0) { itemDao.evictBrowseCacheOlderThan(any(), any()) }
        }

    @Test
    @DisplayName("every Nth write-through sweeps, so a long browsing session stays bounded")
    fun everyNthWriteSweeps() =
        runTest {
            val maintenance = maintenance()

            repeat(BrowseCacheMaintenance.WRITES_BETWEEN_SWEEPS) { maintenance.onWriteThrough() }
            advanceUntilIdle()
            repeat(BrowseCacheMaintenance.WRITES_BETWEEN_SWEEPS) { maintenance.onWriteThrough() }
            advanceUntilIdle()

            coVerify(exactly = 2) { itemDao.trimBrowseCacheTo(any(), any()) }
        }

    @Test
    @DisplayName("writes landing during a sweep do not start a second one")
    fun writesDuringASweepDoNotStackUp() =
        runTest {
            val maintenance = maintenance()

            // No `advanceUntilIdle` between the batches, so the first sweep is still in flight when
            // the second threshold is crossed. An eviction pass that could be started again by the
            // writes it is running behind would multiply exactly when the table is busiest.
            repeat(BrowseCacheMaintenance.WRITES_BETWEEN_SWEEPS * 2) { maintenance.onWriteThrough() }
            advanceUntilIdle()

            coVerify(exactly = 1) { itemDao.trimBrowseCacheTo(any(), any()) }
        }
}
