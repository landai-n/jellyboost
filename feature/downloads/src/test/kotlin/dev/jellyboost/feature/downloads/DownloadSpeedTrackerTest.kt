package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.model.DownloadStatus
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadSpeedTrackerTest {
    private val tracker = DownloadSpeedTracker(smoothing = 1f)

    @Test
    fun `the first sample yields no speed`() {
        val speeds = tracker.update(listOf(downloading("1", bytes = 1_000L)), nowMillis = 0L)

        speeds.shouldBeEmpty()
    }

    @Test
    fun `two samples a second apart give bytes per second`() {
        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 0L)

        val speeds = tracker.update(listOf(downloading("1", bytes = 8_400_000L)), nowMillis = 1_000L)

        speeds["1"] shouldBe 8_400_000L
    }

    @Test
    fun `samples inside the window are accumulated, not extrapolated`() {
        // Short windows are the ones landing between two halves of a single write, so they are
        // held until a full window is up.
        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 0L)

        tracker.update(listOf(downloading("1", bytes = 1_000L)), nowMillis = 500L).shouldBeEmpty()

        tracker.update(listOf(downloading("1", bytes = 2_000L)), nowMillis = 1_000L)["1"] shouldBe 2_000L
    }

    @Test
    fun `a burst of emissions milliseconds apart cannot inflate the speed`() {
        // `DownloadDao.observeAll` is a transaction over `downloads` and `download_files`, written
        // back to back: dividing a 500 ms window's bytes by that 1 ms reports 100–180 MB/s for a
        // transfer running at 8.
        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 0L)
        tracker.update(listOf(downloading("1", bytes = 8_000_000L)), nowMillis = 1_000L)["1"] shouldBe 8_000_000L

        val burst = tracker.update(listOf(downloading("1", bytes = 8_400_000L)), nowMillis = 1_001L)

        burst["1"] shouldBe 8_000_000L
    }

    @Test
    fun `smoothing folds a new sample into the previous answer`() {
        val smoothed = DownloadSpeedTracker(smoothing = 0.5f)
        smoothed.update(listOf(downloading("1", bytes = 0L)), nowMillis = 0L)
        smoothed.update(listOf(downloading("1", bytes = 1_000L)), nowMillis = 1_000L)

        smoothed.update(listOf(downloading("1", bytes = 3_000L)), nowMillis = 2_000L)["1"] shouldBe 1_500L
    }

    @Test
    fun `a restarted file never reports a negative speed`() {
        // A server that ignores `Range` answers 200 and the downloader truncates, so the counter
        // legitimately goes backwards.
        tracker.update(listOf(downloading("1", bytes = 900_000L)), nowMillis = 0L)

        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 1_000L)["1"] shouldBe 0L
    }

    @Test
    fun `two samples at the same instant are ignored`() {
        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 1_000L)

        tracker.update(listOf(downloading("1", bytes = 5_000L)), nowMillis = 1_000L).shouldBeEmpty()
    }

    @Test
    fun `an item that stops downloading stops reporting a speed`() {
        tracker.update(listOf(downloading("1", bytes = 0L)), nowMillis = 0L)
        tracker.update(listOf(downloading("1", bytes = 1_000L)), nowMillis = 1_000L)

        val speeds = tracker.update(listOf(downloading("1", bytes = 1_000L, status = DownloadStatus.PAUSED)), 2_000L)

        speeds shouldNotContainKey "1"
    }

    @Test
    fun `speeds are tracked per item`() {
        tracker.update(
            listOf(downloading("1", bytes = 0L), downloading("2", bytes = 0L)),
            nowMillis = 0L,
        )

        val speeds =
            tracker.update(
                listOf(downloading("1", bytes = 1_000L), downloading("2", bytes = 4_000L)),
                nowMillis = 1_000L,
            )

        speeds["1"] shouldBe 1_000L
        speeds["2"] shouldBe 4_000L
    }

    private fun downloading(
        id: String,
        bytes: Long,
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
    ) = downloadItem(
        itemId = id,
        title = "Arrival",
        seriesName = null,
        status = status,
        bytesDownloaded = bytes,
        bytesTotal = 10_000_000L,
        bytesOnDisk = bytes,
        queuePosition = 0,
    )
}
