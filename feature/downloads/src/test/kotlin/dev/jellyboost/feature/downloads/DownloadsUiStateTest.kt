package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageUsage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DownloadsUiState.queueStats] — the wide-layout "QUEUE" stat panel's numbers
 * (2026 refresh, spec "4d Downloads"). Pure data over [DownloadsUiState.queue] and
 * [DownloadsUiState.speeds], so — like [DownloadRowsTest]'s pure-function block — every assertion
 * here is a plain `DownloadsUiState` construction and a read of the computed [QueueStats], no
 * Compose harness or MockK/Turbine required.
 */
class DownloadsUiStateTest {
    @Test
    fun `an empty queue has nothing to sum and reads as idle`() {
        val state = DownloadsUiState(queue = emptyList())

        state.queueStats shouldBe QueueStats(itemCount = 0, remainingBytes = 0L, bytesPerSecond = 0L, etaSeconds = null)
        state.queueStats.isIdle shouldBe true
    }

    @Test
    fun `item count is the queue's own size`() {
        val state = DownloadsUiState(queue = listOf(queued("1"), queued("2"), queued("3")))

        state.queueStats.itemCount shouldBe 3
    }

    @Test
    fun `remaining bytes sums displayTotalBytes minus bytesDownloaded across every row`() {
        val a = queued("1", bytesDownloaded = 100L, bytesTotal = 300L) // 200 remaining
        val b = queued("2", bytesDownloaded = 50L, bytesTotal = 150L) // 100 remaining
        val state = DownloadsUiState(queue = listOf(a, b))

        state.queueStats.remainingBytes shouldBe 300L
    }

    @Test
    fun `a row already past its own total contributes nothing negative`() {
        // displayTotalBytes clamps a projection into [bytesDownloaded, ceiling] (DownloadItem's own
        // doc), so bytesDownloaded can equal but never exceed it — this pins that a queue summing
        // the difference never goes negative even at that boundary.
        val atItsOwnTotal = queued("1", bytesDownloaded = 300L, bytesTotal = 300L)
        val state = DownloadsUiState(queue = listOf(atItsOwnTotal))

        state.queueStats.remainingBytes shouldBe 0L
    }

    @Test
    fun `bytes per second sums every row's current speed, keyed by item id`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1"), queued("2"), queued("3")),
                speeds = mapOf("1" to 1_000_000L, "2" to 2_500_000L),
                // "3" has no entry — nothing sampled for it yet, contributes zero rather than crashing.
            )

        state.queueStats.bytesPerSecond shouldBe 3_500_000L
    }

    @Test
    fun `a speed entry for a row not on the queue is never summed`() {
        // speeds is keyed by item id across the whole app session (DownloadSpeedTracker), not
        // scoped to the current queue — a stale entry for a row that already finished must not
        // inflate the aggregate.
        val state =
            DownloadsUiState(
                queue = listOf(queued("1")),
                speeds = mapOf("1" to 1_000_000L, "stale" to 9_000_000L),
            )

        state.queueStats.bytesPerSecond shouldBe 1_000_000L
    }

    @Test
    fun `zero total speed means idle and no ETA, rather than a division by zero`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 100L, bytesTotal = 300L)),
                speeds = emptyMap(),
            )

        state.queueStats.isIdle shouldBe true
        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `nothing remaining means no ETA even while transferring`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 300L, bytesTotal = 300L)),
                speeds = mapOf("1" to 1_000_000L),
            )

        state.queueStats.isIdle shouldBe false
        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `an exact division gives a whole number of seconds, ceiling division shared with a row's own ETA`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 400L, bytesTotal = 500L)), // 100 remaining
                speeds = mapOf("1" to 10L),
            )

        state.queueStats.etaSeconds shouldBe 10L
    }

    @Test
    fun `a division with a remainder rounds up, never short`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 399L, bytesTotal = 500L)), // 101 remaining
                speeds = mapOf("1" to 10L),
            )

        state.queueStats.etaSeconds shouldBe 11L
    }

    @Test
    fun `an aggregate estimate beyond 24 hours is guarded out as guesswork, same threshold as a row's own ETA`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 0L, bytesTotal = 86_401L)),
                speeds = mapOf("1" to 1L),
            )

        state.queueStats.etaSeconds shouldBe null
    }

    @Test
    fun `an aggregate estimate exactly at the 24-hour guard is still shown`() {
        val state =
            DownloadsUiState(
                queue = listOf(queued("1", bytesDownloaded = 0L, bytesTotal = 86_400L)),
                speeds = mapOf("1" to 1L),
            )

        state.queueStats.etaSeconds shouldBe 86_400L
    }

    // ---- The precomputed chrome (audit 2026-08-08, PERF-5) --------------------------------------

    @Test
    fun `downloaded bytes are summed once, across every group`() {
        val state =
            DownloadsUiState(
                downloaded =
                    listOf(
                        DownloadGroup(title = "Westworld", items = listOf(finished("1", 100L), finished("2", 200L))),
                        DownloadGroup(title = "Dune", items = listOf(finished("3", 700L))),
                    ),
            )

        state.downloadedBytes shouldBe 1_000L
    }

    @Test
    fun `a group's own size is the sum of its rows`() {
        DownloadGroup(
            title = "Westworld",
            items = listOf(finished("1", 100L), finished("2", 250L)),
        ).bytesOnDisk shouldBe 350L
    }

    @Test
    fun `the storage figure is floored at what the downloaded tab accounts for`() {
        // The filesystem walk is the source, but it runs on a tick: a download that has just landed
        // must not make the screen claim less space used than its own rows add up to.
        val summary =
            storageSummary(storage = StorageUsage(usedBytes = 400L, availableBytes = 600L), downloadedBytes = 900L)

        summary.usedBytes shouldBe 900L
        summary.availableBytes shouldBe 600L
        // The denominator stays the volume — used *as walked* plus free — rather than growing with
        // the floored figure, which would move the bar's end under it.
        summary.totalBytes shouldBe 1_000L
    }

    @Test
    fun `a walk ahead of the tab wins, since it sees files the tab does not`() {
        val summary =
            storageSummary(storage = StorageUsage(usedBytes = 800L, availableBytes = 200L), downloadedBytes = 100L)

        summary.usedBytes shouldBe 800L
        summary.totalBytes shouldBe 1_000L
    }

    @Test
    fun `a not-yet-known total gives an empty bar rather than a division by zero`() {
        usageFraction(used = 500L, total = 0L) shouldBe 0f
    }

    @Test
    fun `a fraction past its own end is clamped`() {
        usageFraction(used = 1_500L, total = 1_000L) shouldBe 1f
    }

    @Test
    fun `the chrome carries the queue's progress, so the wide panel does not re-sum it per frame`() {
        val state =
            DownloadsUiState(
                queue =
                    listOf(
                        queued("1", bytesDownloaded = 250L, bytesTotal = 500L),
                        queued("2", bytesDownloaded = 250L, bytesTotal = 1_500L),
                    ),
            )

        // 500 downloaded against 500 + 1_500 remaining.
        state.chrome.queueProgress shouldBe 0.25f
        state.chrome.hasQueue shouldBe true
        state.chrome.queueStats shouldBe state.queueStats
    }

    @Test
    fun `an empty queue reports no progress and no queue`() {
        val state = DownloadsUiState(queue = emptyList())

        state.chrome.queueProgress shouldBe 0f
        state.chrome.hasQueue shouldBe false
    }

    @Test
    fun `the chrome carries the bulk buttons' own enablement, and nothing else about the queue`() {
        val state =
            DownloadsUiState(
                selectedTab = DownloadsTab.QUEUE,
                queue = listOf(queued("1"), paused("2")),
                wifiOnly = false,
            )

        state.chrome.selectedTab shouldBe DownloadsTab.QUEUE
        state.chrome.canPauseAll shouldBe true
        state.chrome.canResumeAll shouldBe true
        state.chrome.wifiOnly shouldBe false
    }

    private fun finished(
        itemId: String,
        bytesOnDisk: Long,
    ) = DownloadItem(
        itemId = itemId,
        title = "Title $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADED,
        bytesDownloaded = bytesOnDisk,
        bytesTotal = bytesOnDisk,
        bytesOnDisk = bytesOnDisk,
        queuePosition = 0,
    )

    private fun paused(itemId: String) = queued(itemId).copy(status = DownloadStatus.PAUSED)

    @Suppress("LongParameterList")
    private fun queued(
        itemId: String,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 1_000L,
    ) = DownloadItem(
        itemId = itemId,
        title = "Title $itemId",
        seriesName = null,
        status = DownloadStatus.DOWNLOADING,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        bytesOnDisk = 0L,
        queuePosition = 0,
    )
}
