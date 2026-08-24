package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.model.DownloadState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A season has no download row of its own, so this function *is* its download state. */
class AggregateDownloadStateTest {
    @Test
    fun `a season with no episodes has nothing to say`() {
        aggregateDownloadState(emptyList()) shouldBe DownloadState.NotDownloaded
    }

    @Test
    fun `every episode downloaded means the season is downloaded`() {
        aggregateDownloadState(List(3) { DownloadState.Downloaded }) shouldBe DownloadState.Downloaded
    }

    @Test
    fun `a season is only partly downloaded until the last episode lands`() {
        // Not Downloaded: the next tap must enqueue the missing episodes, and a "Remove" over an
        // incomplete season is the worse mistake.
        aggregateDownloadState(
            listOf(DownloadState.Downloaded, DownloadState.Downloaded, DownloadState.NotDownloaded),
        ) shouldBe DownloadState.NotDownloaded
    }

    @Test
    fun `progress is the season's, not the file's that happens to be moving`() {
        aggregateDownloadState(
            listOf(
                DownloadState.Downloaded,
                DownloadState.Downloading(progress = 0.5f),
                DownloadState.NotDownloaded,
                DownloadState.NotDownloaded,
            ),
        ) shouldBe DownloadState.Downloading(progress = 0.375f)
    }

    @Test
    fun `a transfer in flight outranks the episodes still waiting behind it`() {
        aggregateDownloadState(
            listOf(DownloadState.Queued, DownloadState.Downloading(progress = 0f), DownloadState.Queued),
        ) shouldBe DownloadState.Downloading(progress = 0f)
    }

    @Test
    fun `queued and paused episodes both read as queued`() {
        aggregateDownloadState(listOf(DownloadState.Queued, DownloadState.Downloaded)) shouldBe
            DownloadState.Queued
        aggregateDownloadState(listOf(DownloadState.Paused, DownloadState.Downloaded)) shouldBe
            DownloadState.Queued
    }

    @Test
    fun `a failure shows only once nothing is still running`() {
        // While anything transfers the useful button is Cancel; the failed episode is picked up by
        // the retry given once the queue drains.
        aggregateDownloadState(listOf(DownloadState.Failed, DownloadState.Downloading(progress = 0.2f))) shouldBe
            DownloadState.Downloading(progress = 0.1f)
        aggregateDownloadState(listOf(DownloadState.Failed, DownloadState.Downloaded)) shouldBe
            DownloadState.Failed
    }

    @Test
    fun `a season nobody has touched is simply not downloaded`() {
        aggregateDownloadState(List(4) { DownloadState.NotDownloaded }) shouldBe DownloadState.NotDownloaded
    }
}
